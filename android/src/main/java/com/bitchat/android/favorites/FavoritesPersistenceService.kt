package com.bitchat.android.favorites

import java.util.Date

/**
 * Mirror of bitchat's FavoriteRelationship model. Only [isFavorite] is read by
 * the vendored transport, but the full shape is kept for signature parity.
 */
data class FavoriteRelationship(
    val peerNoisePublicKey: ByteArray,
    val peerNostrPublicKey: String?,
    val peerNickname: String,
    val isFavorite: Boolean,
    val theyFavoritedUs: Boolean,
    val favoritedAt: Date,
    val lastUpdated: Date
)

/**
 * SDK stub of bitchat's FavoritesPersistenceService.
 *
 * The real service persists favourite relationships and the Nostr pubkey
 * mapping (which pulls in the Nostr/Bech32 stack the SDK drops). For BLE-only
 * delivery none of this is required: status lookups return null and updates are
 * no-ops. Can be promoted to a real implementation when E2E/favourites land.
 */
class FavoritesPersistenceService private constructor() {

    fun getFavoriteStatus(noisePublicKey: ByteArray): FavoriteRelationship? = null
    fun getFavoriteStatus(peerID: String): FavoriteRelationship? = null

    fun updateNostrPublicKey(noisePublicKey: ByteArray, nostrPubkey: String) {}
    fun updateNostrPublicKeyForPeerID(peerID: String, nostrPubkey: String) {}
    fun updatePeerFavoritedUs(noisePublicKey: ByteArray, theyFavoritedUs: Boolean) {}

    fun findNostrPubkey(forNoiseKey: ByteArray): String? = null
    fun findNostrPubkeyForPeerID(peerID: String): String? = null

    companion object {
        val shared: FavoritesPersistenceService by lazy { FavoritesPersistenceService() }
    }
}
