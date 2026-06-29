package com.bitchat.android.services

import com.bitchat.android.model.BitchatMessage

/**
 * SDK stub of bitchat's AppStateStore.
 *
 * In the full bitchat app this object backs the Compose UI state (public /
 * private / channel message lists and the live transport-peer sets). The mesh
 * transport only writes into it on a best-effort basis (every call site in the
 * vendored code is wrapped in try/catch), so a no-op stub is safe: it keeps the
 * transport compiling and running while the SDK surfaces messages through its
 * own listener instead.
 */
object AppStateStore {
    fun setTransportPeers(transportId: String, ids: List<String>) {}
    fun clearTransportPeers(transportId: String) {}
    fun setTransportDirectPeers(transportId: String, ids: Collection<String>) {}
    fun clearTransportDirectPeers(transportId: String) {}
    fun getDirectPeers(): Set<String> = emptySet()

    fun addPublicMessage(msg: BitchatMessage) {}
    fun addPrivateMessage(peerID: String, msg: BitchatMessage) {}
    fun addChannelMessage(channel: String, msg: BitchatMessage) {}
}
