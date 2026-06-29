package com.example.meshchat.sdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.BluetoothMeshDelegate
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.services.NicknameProvider
import com.bitchat.android.ui.debug.DebugPreferenceManager
import java.util.Date

/**
 * A mesh chat message passed up (to React Native).
 *
 * Addressing is by string peerID, which makes it possible to support private
 * messages plus delivery and read receipts.
 */
data class MeshChatMessage(
    val id: String,
    val senderPeerID: String?,
    val sender: String,
    val content: String,
    val timestamp: Long,
    val isPrivate: Boolean,
    val channel: String?,
    val isRelay: Boolean,
)

interface MeshChatListener {
    fun onMessageReceived(message: MeshChatMessage)
    fun onPeerListUpdated(peers: List<String>) {}
    fun onDeliveryAck(messageID: String, peerID: String) {}
    fun onReadReceipt(messageID: String, peerID: String) {}
    /** An end-to-end Noise session with a peer was established/torn down (for the encryption indicator). */
    fun onNoiseSession(peerID: String, established: Boolean) {}
    fun onError(error: Throwable)
    fun onLog(message: String) {}
}

/**
 * A real mesh chat on top of the BLE mesh stack ported from bitchat.
 *
 * The transport establishes real GATT connections (server + client), splits
 * large packets into fragments (MTU 512), stores messages for offline peers
 * (store-and-forward), and relays packets across the network with TTL and deduplication.
 */
class MeshChatSdk(
    context: Context,
    private val listener: MeshChatListener,
    nickname: String? = null,
    /**
     * Whether to keep mesh alive in the background via a foreground service with a
     * persistent notification. Enabled by default — otherwise Android puts the process
     * to sleep and the network stops working when the app is minimized.
     */
    private val keepAliveInBackground: Boolean = true,
    /**
     * Whether to show a local notification for an incoming message when the app is
     * backgrounded (in the foreground the UI already shows the message in the list).
     * Can be changed at runtime via [setNotificationsEnabled].
     */
    notifyInBackground: Boolean = true,
) {

    companion object {
        private const val TAG = "MeshChatSdk"
        private const val GOSSIP_OWNER = "MeshChatSdk"
    }

    /** Current state of push notifications for incoming messages (mutable). */
    @Volatile
    private var notifyInBackground: Boolean = notifyInBackground

    /** Enable/disable local notifications for incoming messages. */
    fun setNotificationsEnabled(enabled: Boolean) {
        notifyInBackground = enabled
        Log.i(TAG, "notifyInBackground=$enabled")
    }

    private val appContext = context.applicationContext

    @Volatile
    private var started = false

    /** Whether we're in the background — used to decide whether to post a local notification. */
    @Volatile
    private var appInForeground = true

    init {
        // Enable persistence of settings/identity (best-effort).
        try { DebugPreferenceManager.init(appContext) } catch (_: Exception) {}
        nickname?.let { NicknameProvider.setNickname(it) }

        // Track the whole process's foreground/background state (on the main thread).
        Handler(Looper.getMainLooper()).post {
            try {
                appInForeground = ProcessLifecycleOwner.get().lifecycle.currentState
                    .isAtLeast(Lifecycle.State.STARTED)
                ProcessLifecycleOwner.get().lifecycle.addObserver(
                    LifecycleEventObserver { _: LifecycleOwner, event: Lifecycle.Event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> appInForeground = true
                            Lifecycle.Event.ON_STOP -> appInForeground = false
                            else -> {}
                        }
                    }
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to observe process lifecycle: ${t.message}")
            }
        }
    }

    private val meshService = BluetoothMeshService(appContext)

    // Persistent store of verified fingerprints — the same EncryptedSharedPreferences
    // the mesh core uses (reads/writes the verified_fingerprints key).
    private val identityStore = SecureIdentityStateManager(appContext)

    init {
        // Notify upward when a Noise session with a peer is established (for the 🔒 icon).
        meshService.setOnNoiseSessionEstablished { peerID ->
            Log.i(TAG, "🔒 Noise session established with $peerID")
            listener.onNoiseSession(peerID, true)
        }
        meshService.delegate = object : BluetoothMeshDelegate {
            override fun didReceiveMessage(message: BitchatMessage) {
                Log.i(TAG, "📥 RX message from=${message.senderPeerID} private=${message.isPrivate} " +
                    "relay=${message.isRelay} content='${message.content.take(60)}'")
                listener.onMessageReceived(message.toMeshChatMessage())
                // In the background JS sleeps — show a native notification for the incoming message.
                if (notifyInBackground && !appInForeground) {
                    try {
                        MeshNotifier.notifyMessage(
                            appContext,
                            sender = message.sender,
                            content = message.content,
                            threadKey = message.senderPeerID ?: message.sender
                        )
                    } catch (t: Throwable) {
                        Log.w(TAG, "notifyMessage failed: ${t.message}")
                    }
                }
            }

            override fun didUpdatePeerList(peers: List<String>) {
                Log.i(TAG, "👥 peer list updated: count=${peers.size} peers=$peers")
                listener.onPeerListUpdated(peers)
            }

            override fun didReceiveChannelLeave(channel: String, fromPeer: String) {}

            override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
                listener.onDeliveryAck(messageID, recipientPeerID)
            }

            override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
                listener.onReadReceipt(messageID, recipientPeerID)
            }

            override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {}
            override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {}

            // Password-protected channels are phase 2 (together with encryption); we don't decrypt for now.
            override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? = null

            override fun getNickname(): String? =
                NicknameProvider.getNickname(appContext, meshService.myPeerID)

            override fun isFavorite(peerID: String): Boolean = false
        }
    }

    /** This node's identifier in the mesh network (stable, tied to the key). */
    val myPeerID: String get() = meshService.myPeerID

    fun setNickname(nickname: String?) {
        NicknameProvider.setNickname(nickname)
    }

    /**
     * Battery saver mode. When enabled, mesh runs less often in the background
     * (POWER_SAVER): lower drain, but background delivery is less prompt.
     * Disabled by default (we hold BALANCED in the background).
     */
    fun setBatterySaver(enabled: Boolean) {
        com.bitchat.android.mesh.PowerManager.setBatterySaver(enabled)
        Log.i(TAG, "batterySaver=$enabled")
    }

    fun isBatterySaverEnabled(): Boolean =
        com.bitchat.android.mesh.PowerManager.batterySaverEnabled

    fun start() {
        if (started) {
            Log.w(TAG, "start() ignored: already started")
            return
        }
        started = true
        try {
            Log.i(TAG, "▶️ start() peerID=${meshService.myPeerID} nickname=${NicknameProvider.getNickname(appContext, meshService.myPeerID)}")
            MeshServiceHolder.meshService = meshService
            meshService.startServices()
            MeshServiceHolder.startSharedGossip(GOSSIP_OWNER)
            // Announce ourselves right away so neighboring nodes can see us.
            meshService.sendBroadcastAnnounce()
            // Foreground service: keep the process alive in the background.
            if (keepAliveInBackground) {
                try { MeshForegroundService.start(appContext) } catch (t: Throwable) {
                    Log.w(TAG, "Failed to start foreground service: ${t.message}")
                }
            }
            Log.i(TAG, "✅ start() done. ${transportStatus()}")
            listener.onLog("MeshChatSdk started (peerID=${meshService.myPeerID})")
        } catch (t: Throwable) {
            started = false
            Log.e(TAG, "❌ Failed to start mesh service", t)
            listener.onError(t)
        }
    }

    /** A short summary of the transport state — for diagnostics. */
    private fun transportStatus(): String = try {
        "activePeers=${meshService.getActivePeerCount()} peers=${meshService.getPeerNicknames().keys}"
    } catch (t: Throwable) {
        "status unavailable: ${t.message}"
    }

    /** Full debug status of the transport (can be called from RN). */
    fun debugStatus(): String = try {
        meshService.getDebugStatus()
    } catch (t: Throwable) {
        "debugStatus unavailable: ${t.message}"
    }

    fun stop() {
        if (!started) return
        started = false
        try {
            if (keepAliveInBackground) {
                try { MeshForegroundService.stop(appContext) } catch (_: Throwable) {}
            }
            MeshServiceHolder.stopSharedGossip(GOSSIP_OWNER)
            meshService.stopServices()
            if (MeshServiceHolder.meshService === meshService) {
                MeshServiceHolder.meshService = null
            }
            listener.onLog("MeshChatSdk stopped")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to stop mesh service", t)
            listener.onError(t)
        }
    }

    /** Broadcast message to all nodes on the network. */
    fun sendBroadcastMessage(text: String) {
        Log.i(TAG, "📤 sendBroadcast len=${text.length} started=$started ${transportStatus()}")
        if (!ensureStarted()) return
        try {
            meshService.sendMessage(text)
            Log.i(TAG, "📤 sendBroadcast -> meshService.sendMessage() returned ok")
            // Local echo: the transport only pushes the packet into the network and doesn't
            // return our own message via didReceiveMessage, so we show it to the sender
            // ourselves — otherwise it looks like "the message wasn't sent".
            echoOwnMessage(text, isPrivate = false, recipientNickname = null)
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Failed to send broadcast message", t)
            listener.onError(t)
        }
    }

    /** Private message to a specific node by its peerID. */
    fun sendPrivateMessage(text: String, recipientPeerID: String, recipientNickname: String) {
        Log.i(TAG, "📤 sendPrivate to=$recipientPeerID len=${text.length} started=$started ${transportStatus()}")
        if (!ensureStarted()) return
        try {
            meshService.sendPrivateMessage(text, recipientPeerID, recipientNickname)
            Log.i(TAG, "📤 sendPrivate -> meshService.sendPrivateMessage() returned ok")
            echoOwnMessage(text, isPrivate = true, recipientNickname = recipientNickname)
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Failed to send private message", t)
            listener.onError(t)
        }
    }

    // --- End-to-end encryption (Noise) and peer verification ---

    /**
     * Initiate a Noise handshake with a peer. Must be called before sending the first
     * private message: `sendPrivateMessage` is fire-and-forget and, if there's no session
     * yet, the message will NOT be sent (only the handshake is kicked off).
     * Session establishment is reported via onNoiseSession(peerID, true).
     */
    fun startHandshake(peerID: String) {
        if (!ensureStarted()) return
        Log.i(TAG, "🤝 startHandshake with $peerID")
        meshService.initiateNoiseHandshake(peerID)
    }

    /** Whether an established (encrypted) Noise session exists with the peer. */
    fun hasSession(peerID: String): Boolean = try {
        meshService.hasEstablishedSession(peerID)
    } catch (_: Exception) { false }

    /** Peer's key fingerprint (SHA-256 of the static Noise key) — for out-of-band comparison. */
    fun getPeerFingerprint(peerID: String): String? = try {
        meshService.getPeerFingerprint(peerID)
    } catch (_: Exception) { null }

    /** Our own key fingerprint (the other party compares it). */
    fun getMyFingerprint(): String? = try {
        meshService.getIdentityFingerprint()
    } catch (_: Exception) { null }

    /** Whether the peer is marked verified — by fingerprint, stored locally. */
    fun isPeerVerified(peerID: String): Boolean {
        return try {
            val fp = meshService.getPeerFingerprint(peerID) ?: return false
            identityStore.isVerifiedFingerprint(fp)
        } catch (_: Exception) {
            false
        }
    }

    /** Mark/unmark a peer as verified (after comparing fingerprints out of band). */
    fun setPeerVerified(peerID: String, verified: Boolean) {
        try {
            val fp = meshService.getPeerFingerprint(peerID)
            if (fp == null) {
                Log.w(TAG, "setPeerVerified: no fingerprint for $peerID (session not established?)")
                return
            }
            identityStore.setVerifiedFingerprint(fp, verified)
            Log.i(TAG, "🛡️ peer $peerID verified=$verified (fp=${fp.take(16)}…)")
        } catch (t: Throwable) {
            Log.e(TAG, "setPeerVerified failed", t)
        }
    }

    /** Peer's nickname by its peerID (or null if unknown). */
    fun getPeerNickname(peerID: String): String? = getPeerNicknames()[peerID]

    private fun echoOwnMessage(text: String, isPrivate: Boolean, recipientNickname: String?) {
        Log.i(TAG, "🪞 echo own message to listener (private=$isPrivate)")
        val myNick = NicknameProvider.getNickname(appContext, meshService.myPeerID)
        val echo = BitchatMessage(
            sender = myNick,
            content = text,
            timestamp = Date(),
            senderPeerID = meshService.myPeerID,
            isPrivate = isPrivate,
            recipientNickname = recipientNickname,
        )
        listener.onMessageReceived(echo.toMeshChatMessage())
    }

    fun getPeerNicknames(): Map<String, String> = try {
        meshService.getPeerNicknames()
    } catch (_: Exception) {
        emptyMap()
    }

    private fun ensureStarted(): Boolean {
        if (!started) {
            Log.e(TAG, "⛔ send blocked: MeshChatSdk is not started")
            listener.onError(IllegalStateException("MeshChatSdk is not started"))
            return false
        }
        return true
    }

    private fun BitchatMessage.toMeshChatMessage() = MeshChatMessage(
        id = id,
        senderPeerID = senderPeerID,
        sender = sender,
        content = content,
        timestamp = timestamp.time,
        isPrivate = isPrivate,
        channel = channel,
        isRelay = isRelay,
    )
}
