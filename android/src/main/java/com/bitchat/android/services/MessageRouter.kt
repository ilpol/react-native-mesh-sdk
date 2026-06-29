package com.bitchat.android.services

import com.bitchat.android.model.ReadReceipt

/**
 * SDK stub of bitchat's MessageRouter.
 *
 * The real MessageRouter bridges the BLE mesh with the internet (Nostr)
 * transport for geohash channels. The SDK is BLE-only, so [tryGetInstance]
 * always returns null and callers fall back to delivering over the mesh.
 */
class MessageRouter private constructor() {
    fun sendReadReceipt(receipt: ReadReceipt, toPeerID: String) {}

    companion object {
        fun tryGetInstance(): MessageRouter? = null
    }
}
