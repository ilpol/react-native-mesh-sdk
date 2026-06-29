package com.bitchat.android.services

import android.content.Context

/**
 * SDK stub of bitchat's NicknameProvider.
 *
 * The real implementation reads the nickname from the app's DataManager
 * (Compose UI layer). The SDK keeps an in-memory nickname configurable via
 * [setNickname]; if none is set it falls back to the peerID, matching the
 * original contract.
 */
object NicknameProvider {
    @Volatile
    private var nickname: String? = null

    fun setNickname(value: String?) { nickname = value?.takeIf { it.isNotBlank() } }

    fun getNickname(context: Context, myPeerID: String): String {
        return nickname ?: myPeerID
    }
}
