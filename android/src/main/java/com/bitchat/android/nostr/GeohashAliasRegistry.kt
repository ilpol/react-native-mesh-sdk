package com.bitchat.android.nostr

/**
 * SDK stub of bitchat's GeohashAliasRegistry.
 *
 * Maps ephemeral geohash-channel peer aliases (delivered over Nostr) to their
 * routing keys. The SDK is BLE-only, so the registry is always empty and
 * [snapshot] returns no aliases.
 */
object GeohashAliasRegistry {
    fun snapshot(): Map<String, String> = emptyMap()
}
