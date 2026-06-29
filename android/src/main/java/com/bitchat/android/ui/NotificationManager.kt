package com.bitchat.android.ui

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.bitchat.android.util.NotificationIntervalManager

/**
 * SDK stub of bitchat's service-level NotificationManager.
 *
 * The full app posts system notifications for background DMs. The SDK leaves
 * notification UX to the host React-Native app, so these are no-ops. The
 * constructor signature is kept identical so the vendored transport wiring
 * compiles unchanged.
 */
class NotificationManager(
    private val context: Context,
    private val notificationManager: NotificationManagerCompat,
    private val notificationIntervalManager: NotificationIntervalManager
) {
    fun setAppBackgroundState(inBackground: Boolean) {}

    fun showPrivateMessageNotification(
        senderPeerID: String,
        senderNickname: String,
        messageContent: String
    ) {}
}
