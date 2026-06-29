import Foundation

// MARK: - MeshChat SDK stubs
// Stubs for types from the bitchat-iOS app that aren't vendored into the SDK
// (UI/Nostr/Tor/app glue). They contain exactly what the mesh core uses.

/// Nostr identity stub: the SDK is BLE-only, npub is not used over the network.
struct NostrIdentity {
    let npub: String
    let publicKeyHex: String
    init(npub: String = "", publicKeyHex: String = "") {
        self.npub = npub
        self.publicKeyHex = publicKeyHex
    }
}

/// Stub of the bridge to the Nostr identity. In a BLE-only build it always returns nil,
/// so the favorite-notification code simply doesn't add an npub.
final class NostrIdentityBridge {
    init(keychain: KeychainManagerProtocol = KeychainManager()) {}
    func getCurrentNostrIdentity() throws -> NostrIdentity? { nil }
    func getNostrPublicKey(for noisePublicKey: Data) -> String? { nil }
    func associateNostrIdentity(_ nostrPubkey: String, with noisePublicKey: Data) {}
    func clearAllAssociations() {}
}

/// Replaces the bitchat App wrapper: only the identifiers for Keychain.
enum BitchatApp {
    /// Service name for Keychain. We take the host app's (RN app's) bundleID.
    static let bundleID: String = Bundle.main.bundleIdentifier ?? "chat.meshchat.sdk"
    /// App-group id (used only if group access is enabled; not required for the SDK).
    static let groupID: String = "group.\(Bundle.main.bundleIdentifier ?? "chat.meshchat.sdk")"
}
