import Foundation
import CoreBluetooth
import UIKit
import UserNotifications

/// A mesh chat message passed up (to React Native).
/// Addressing by string peerID — same as in the Android SDK.
public struct MeshChatMessage {
    public let id: String
    public let senderPeerID: String?
    public let sender: String
    public let content: String
    public let timestamp: TimeInterval   // ms since epoch
    public let isPrivate: Bool
    public let isRelay: Bool
}

public protocol MeshChatListener: AnyObject {
    func onMessageReceived(_ message: MeshChatMessage)
    func onPeerListUpdated(_ peers: [String])
    /// An end-to-end Noise session has been established with a peer (for the encryption indicator).
    func onNoiseSession(_ peerID: String, established: Bool)
    func onError(_ error: Error)
    func onLog(_ message: String)
}

public extension MeshChatListener {
    func onPeerListUpdated(_ peers: [String]) {}
    func onNoiseSession(_ peerID: String, established: Bool) {}
    func onLog(_ message: String) {}
}

/// A real mesh chat on top of the BLE stack ported from bitchat-iOS (`BLEService`).
/// GATT connections, fragmentation, store-and-forward, TTL relay, deduplication —
/// the same core as on Android, protocol-compatible.
public final class MeshChatSdk: NSObject {

    public weak var listener: MeshChatListener?

    private let ble: BLEService
    /// The same identity manager that BLEService uses — for verified fingerprints.
    private let identityManager: SecureIdentityStateManager
    private var started = false
    private var nickname: String
    /// Whether to show a local notification for an incoming message when the app is backgrounded.
    /// Changed at runtime via setNotificationsEnabled.
    private var notifyInBackground: Bool

    /// Enable/disable local notifications for incoming messages.
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
        // Notify upward about Noise session establishment (for the 🔒 icon).
        self.ble.addPeerAuthenticatedHandler { [weak self] peerID, _ in
            self?.listener?.onNoiseSession(peerID.id, established: true)
        }
        if notifyInBackground {
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
        }
    }

    /// Local notification for an incoming message — only when the app is not in the foreground.
    private func postLocalNotificationIfBackground(sender: String, content: String) {
        guard notifyInBackground else { return }
        DispatchQueue.main.async {
            guard UIApplication.shared.applicationState != .active else { return }
            let c = UNMutableNotificationContent()
            c.title = sender.isEmpty ? "New message" : sender
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

    /// Battery saver mode. On iOS background BLE is throttled by the system itself,
    /// so this method just stores the preference for API uniformity (behavior is
    /// mostly governed by iOS). The real effect is on Android.
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

    /// Broadcast message to all nodes on the network.
    public func sendBroadcastMessage(_ text: String) {
        guard ensureStarted() else { return }
        ble.sendMessage(text, mentions: [])
        echoOwnMessage(text, isPrivate: false)
    }

    /// Private message to a specific node by its peerID.
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

    // MARK: - End-to-end encryption (Noise) and peer verification

    /// Initiate a Noise handshake with a peer (needed before the first private message:
    /// sendPrivateMessage is fire-and-forget and without a session the message won't go out).
    public func startHandshake(_ peerID: String) {
        guard ensureStarted() else { return }
        ble.triggerHandshake(with: PeerID(str: peerID))
    }

    /// Whether an established (encrypted) Noise session exists with the peer.
    public func hasSession(_ peerID: String) -> Bool {
        if case .established = ble.getNoiseSessionState(for: PeerID(str: peerID)) {
            return true
        }
        return false
    }

    /// Peer's key fingerprint (for out-of-band comparison); nil until there's a session.
    public func getPeerFingerprint(_ peerID: String) -> String? {
        return ble.getFingerprint(for: PeerID(str: peerID))
    }

    /// Our own key fingerprint.
    public func getMyFingerprint() -> String? {
        return ble.noiseIdentityFingerprint()
    }

    /// Whether the peer is marked verified — by fingerprint, stored locally.
    public func isPeerVerified(_ peerID: String) -> Bool {
        guard let fp = ble.getFingerprint(for: PeerID(str: peerID)) else { return false }
        return identityManager.isVerified(fingerprint: fp)
    }

    /// Mark/unmark a peer as verified (after comparing fingerprints out of band).
    public func setPeerVerified(_ peerID: String, _ verified: Bool) {
        guard let fp = ble.getFingerprint(for: PeerID(str: peerID)) else { return }
        identityManager.setVerified(fingerprint: fp, verified: verified)
    }

    /// Peer's nickname by peerID (or nil).
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

    /// Local echo: the transport doesn't return our own message via the delegate,
    /// so we show it to the sender ourselves.
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
    func didReceiveNoisePayload(from peerID: PeerID, type: NoisePayloadType, payload: Data, timestamp: Date) {
        // Decrypted Noise payloads arrive here. Without this, incoming private
        // messages were decrypted but never surfaced to JS (Android→iOS PMs
        // silently dropped).
        switch type {
        case .privateMessage:
            guard let pm = PrivateMessagePacket.decode(from: payload) else {
                listener?.onLog("Failed to decode private message from \(peerID.id)")
                return
            }
            let senderNickname = ble.getPeerNicknames()[peerID] ?? peerID.id
            let msg = MeshChatMessage(
                id: pm.messageID,
                senderPeerID: peerID.id,
                sender: senderNickname,
                content: pm.content,
                timestamp: timestamp.timeIntervalSince1970 * 1000,
                isPrivate: true,
                isRelay: false
            )
            listener?.onMessageReceived(msg)
            postLocalNotificationIfBackground(sender: senderNickname, content: pm.content)
        default:
            // Delivery acks / read receipts / file transfers are not surfaced to JS yet.
            break
        }
    }
    func didUpdateBluetoothState(_ state: CBManagerState) {}
}
