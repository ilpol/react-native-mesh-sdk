import Foundation

// MARK: - MeshChat SDK stubs
// Заглушки для типов из приложения bitchat-iOS, которые не вендорятся в SDK
// (UI/Nostr/Tor/app-обвязка). Содержат ровно то, что использует mesh-ядро.

/// Заглушка Nostr-идентичности: SDK BLE-only, npub не используется по сети.
struct NostrIdentity {
    let npub: String
    let publicKeyHex: String
    init(npub: String = "", publicKeyHex: String = "") {
        self.npub = npub
        self.publicKeyHex = publicKeyHex
    }
}

/// Заглушка моста к Nostr-идентичности. В BLE-only сборке всегда возвращает nil,
/// поэтому код favorite-уведомлений просто не добавляет npub.
final class NostrIdentityBridge {
    init(keychain: KeychainManagerProtocol = KeychainManager()) {}
    func getCurrentNostrIdentity() throws -> NostrIdentity? { nil }
    func getNostrPublicKey(for noisePublicKey: Data) -> String? { nil }
    func associateNostrIdentity(_ nostrPubkey: String, with noisePublicKey: Data) {}
    func clearAllAssociations() {}
}

/// Заменяет bitchat App-обёртку: только идентификаторы для Keychain.
enum BitchatApp {
    /// Сервисное имя для Keychain. Берём bundleID хост-приложения (RN-приложения).
    static let bundleID: String = Bundle.main.bundleIdentifier ?? "chat.meshchat.sdk"
    /// App-group id (используется только если включён доступ по группе; для SDK не требуется).
    static let groupID: String = "group.\(Bundle.main.bundleIdentifier ?? "chat.meshchat.sdk")"
}
