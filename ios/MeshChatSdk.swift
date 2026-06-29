import Foundation
import CoreBluetooth
import UIKit
import UserNotifications

/// Сообщение mesh-чата, отдаваемое наверх (в React Native).
/// Адресация по строковому peerID — как в Android-SDK.
public struct MeshChatMessage {
    public let id: String
    public let senderPeerID: String?
    public let sender: String
    public let content: String
    public let timestamp: TimeInterval   // мс с эпохи
    public let isPrivate: Bool
    public let isRelay: Bool
}

public protocol MeshChatListener: AnyObject {
    func onMessageReceived(_ message: MeshChatMessage)
    func onPeerListUpdated(_ peers: [String])
    /// Установлена сквозная Noise-сессия с пиром (для индикации шифрования).
    func onNoiseSession(_ peerID: String, established: Bool)
    func onError(_ error: Error)
    func onLog(_ message: String)
}

public extension MeshChatListener {
    func onPeerListUpdated(_ peers: [String]) {}
    func onNoiseSession(_ peerID: String, established: Bool) {}
    func onLog(_ message: String) {}
}

/// Реальный mesh-чат поверх портированного из bitchat-iOS BLE-стека (`BLEService`).
/// GATT-соединения, фрагментация, store-and-forward, релей с TTL, дедупликация —
/// то же ядро, что и на Android, протокол-совместимое.
public final class MeshChatSdk: NSObject {

    public weak var listener: MeshChatListener?

    private let ble: BLEService
    /// Тот же менеджер идентичности, что использует BLEService — для verified-отпечатков.
    private let identityManager: SecureIdentityStateManager
    private var started = false
    private var nickname: String
    /// Показывать локальное уведомление о входящем, когда приложение в фоне.
    /// Меняется в рантайме через setNotificationsEnabled.
    private var notifyInBackground: Bool

    /// Включить/выключить локальные уведомления о входящих сообщениях.
    public func setNotificationsEnabled(_ enabled: Bool) {
        notifyInBackground = enabled
        if enabled {
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
        }
    }

    public var myPeerID: String { ble.myPeerID.id }

    public init(listener: MeshChatListener?, nickname: String? = nil, notifyInBackground: Bool = true) {
        self.listener = listener
        self.nickname = nickname ?? "ios-user"
        self.notifyInBackground = notifyInBackground
        let keychain = KeychainManager()
        let idBridge = NostrIdentityBridge(keychain: keychain)
        let identityManager = SecureIdentityStateManager(keychain)
        self.identityManager = identityManager
        self.ble = BLEService(keychain: keychain, idBridge: idBridge, identityManager: identityManager)
        super.init()
        self.ble.delegate = self
        // Сообщаем наверх об установлении Noise-сессии (для иконки 🔒).
        self.ble.addPeerAuthenticatedHandler { [weak self] peerID, _ in
            self?.listener?.onNoiseSession(peerID.id, established: true)
        }
        if notifyInBackground {
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
        }
    }

    /// Локальное уведомление о входящем — только когда приложение не на переднем плане.
    private func postLocalNotificationIfBackground(sender: String, content: String) {
        guard notifyInBackground else { return }
        DispatchQueue.main.async {
            guard UIApplication.shared.applicationState != .active else { return }
            let c = UNMutableNotificationContent()
            c.title = sender.isEmpty ? "Новое сообщение" : sender
            c.body = content
            c.sound = .default
            let req = UNNotificationRequest(identifier: UUID().uuidString, content: c, trigger: nil)
            UNUserNotificationCenter.current().add(req, withCompletionHandler: nil)
        }
    }

    public func setNickname(_ nickname: String) {
        self.nickname = nickname
        ble.setNickname(nickname)
    }

    /// Режим экономии батареи. На iOS фоновый BLE троттлится самой системой,
    /// поэтому метод сохраняет предпочтение для единообразия API (поведение
    /// в основном управляется iOS). Реальный эффект — на Android.
    public private(set) var batterySaverEnabled = false
    public func setBatterySaver(_ enabled: Bool) {
        batterySaverEnabled = enabled
    }

    public func start() {
        guard !started else { return }
        started = true
        ble.setNickname(nickname)
        ble.startServices()
        ble.sendBroadcastAnnounce()
        listener?.onLog("MeshChatSdk started (peerID=\(ble.myPeerID.id))")
    }

    public func stop() {
        guard started else { return }
        started = false
        ble.stopServices()
        listener?.onLog("MeshChatSdk stopped")
    }

    /// Широковещательное сообщение всем узлам сети.
    public func sendBroadcastMessage(_ text: String) {
        guard ensureStarted() else { return }
        ble.sendMessage(text, mentions: [])
        echoOwnMessage(text, isPrivate: false)
    }

    /// Приватное сообщение конкретному узлу по его peerID.
    public func sendPrivateMessage(_ text: String, to recipientPeerID: String, recipientNickname: String) {
        guard ensureStarted() else { return }
        let messageID = UUID().uuidString
        ble.sendPrivateMessage(text, to: PeerID(str: recipientPeerID), recipientNickname: recipientNickname, messageID: messageID)
        echoOwnMessage(text, isPrivate: true)
    }

    public func getPeerNicknames() -> [String: String] {
        var result: [String: String] = [:]
        for (peer, nick) in ble.getPeerNicknames() { result[peer.id] = nick }
        return result
    }

    // MARK: - Сквозное шифрование (Noise) и верификация пиров

    /// Инициировать Noise-handshake с пиром (нужно перед первым приватным сообщением:
    /// sendPrivateMessage работает fire-and-forget и без сессии сообщение не уйдёт).
    public func startHandshake(_ peerID: String) {
        guard ensureStarted() else { return }
        ble.triggerHandshake(with: PeerID(str: peerID))
    }

    /// Есть ли установленная (зашифрованная) Noise-сессия с пиром.
    public func hasSession(_ peerID: String) -> Bool {
        if case .established = ble.getNoiseSessionState(for: PeerID(str: peerID)) {
            return true
        }
        return false
    }

    /// Отпечаток ключа пира (для сверки вне сети); nil, пока нет сессии.
    public func getPeerFingerprint(_ peerID: String) -> String? {
        return ble.getFingerprint(for: PeerID(str: peerID))
    }

    /// Наш собственный отпечаток ключа.
    public func getMyFingerprint() -> String? {
        return ble.noiseIdentityFingerprint()
    }

    /// Помечен ли пир доверенным (verified) — по отпечатку, хранится локально.
    public func isPeerVerified(_ peerID: String) -> Bool {
        guard let fp = ble.getFingerprint(for: PeerID(str: peerID)) else { return false }
        return identityManager.isVerified(fingerprint: fp)
    }

    /// Пометить/снять пир доверенным (после сверки отпечатков вне сети).
    public func setPeerVerified(_ peerID: String, _ verified: Bool) {
        guard let fp = ble.getFingerprint(for: PeerID(str: peerID)) else { return }
        identityManager.setVerified(fingerprint: fp, verified: verified)
    }

    /// Никнейм пира по peerID (или nil).
    public func getPeerNickname(_ peerID: String) -> String? {
        return getPeerNicknames()[peerID]
    }

    public func debugStatus() -> String {
        "peerID=\(ble.myPeerID.id) started=\(started) peers=\(ble.getPeerNicknames().keys.map { $0.id })"
    }

    // MARK: - Private

    private func ensureStarted() -> Bool {
        if !started {
            listener?.onError(NSError(domain: "MeshChatSdk", code: 1,
                                      userInfo: [NSLocalizedDescriptionKey: "MeshChatSdk is not started"]))
            return false
        }
        return true
    }

    /// Локальное эхо: транспорт не возвращает собственное сообщение через делегат,
    /// поэтому показываем его отправителю сами.
    private func echoOwnMessage(_ text: String, isPrivate: Bool) {
        let msg = MeshChatMessage(
            id: UUID().uuidString,
            senderPeerID: ble.myPeerID.id,
            sender: nickname,
            content: text,
            timestamp: Date().timeIntervalSince1970 * 1000,
            isPrivate: isPrivate,
            isRelay: false
        )
        listener?.onMessageReceived(msg)
    }
}

// MARK: - BitchatDelegate

extension MeshChatSdk: BitchatDelegate {
    func didReceiveMessage(_ message: BitchatMessage) {
        let msg = MeshChatMessage(
            id: message.id,
            senderPeerID: message.senderPeerID?.id,
            sender: message.sender,
            content: message.content,
            timestamp: message.timestamp.timeIntervalSince1970 * 1000,
            isPrivate: message.isPrivate,
            isRelay: message.isRelay
        )
        listener?.onMessageReceived(msg)
        postLocalNotificationIfBackground(sender: message.sender, content: message.content)
    }

    func didReceivePublicMessage(from peerID: PeerID, nickname: String, content: String, timestamp: Date, messageID: String?) {
        let msg = MeshChatMessage(
            id: messageID ?? UUID().uuidString,
            senderPeerID: peerID.id,
            sender: nickname,
            content: content,
            timestamp: timestamp.timeIntervalSince1970 * 1000,
            isPrivate: false,
            isRelay: false
        )
        listener?.onMessageReceived(msg)
        postLocalNotificationIfBackground(sender: nickname, content: content)
    }

    func didUpdatePeerList(_ peers: [PeerID]) {
        listener?.onPeerListUpdated(peers.map { $0.id })
    }

    func didConnectToPeer(_ peerID: PeerID) {}
    func didDisconnectFromPeer(_ peerID: PeerID) {}
    func isFavorite(fingerprint: String) -> Bool { false }
    func didUpdateMessageDeliveryStatus(_ messageID: String, status: DeliveryStatus) {}
    func didReceiveNoisePayload(from peerID: PeerID, type: NoisePayloadType, payload: Data, timestamp: Date) {}
    func didUpdateBluetoothState(_ state: CBManagerState) {}
}
