package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage

/**
 * SDK stub of bitchat's NotificationTextUtils. Returns a plain preview of the
 * message content (the full app adds richer formatting for the notification UI).
 */
object NotificationTextUtils {
    fun buildPrivateMessagePreview(message: BitchatMessage): String = message.content
}
