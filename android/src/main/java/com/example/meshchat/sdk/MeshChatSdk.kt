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
 * Сообщение mesh-чата, отдаваемое наверх (в React Native).
 *
 * Адресация идёт по строковому peerID, что позволяет поддержать приватные
 * сообщения, подтверждения доставки и прочтения.
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
    /** Установлена/сброшена сквозная Noise-сессия с пиром (для индикации шифрования). */
    fun onNoiseSession(peerID: String, established: Boolean) {}
    fun onError(error: Throwable)
    fun onLog(message: String) {}
}

/**
 * Реальный mesh-чат поверх портированного из bitchat BLE-mesh стека.
 *
 * Транспорт устанавливает настоящие GATT-соединения (server + client), режет
 * крупные пакеты на фрагменты (MTU 512), хранит сообщения для офлайн-пиров
 * (store-and-forward) и ретранслирует пакеты по сети с TTL и дедупликацией.
 */
class MeshChatSdk(
    context: Context,
    private val listener: MeshChatListener,
    nickname: String? = null,
    /**
     * Держать ли mesh живым в фоне через foreground-сервис с постоянным
     * уведомлением. По умолчанию включено — иначе Android усыпит процесс и
     * сеть перестанет работать при свёрнутом приложении.
     */
    private val keepAliveInBackground: Boolean = true,
    /**
     * Показывать ли локальное уведомление о входящем сообщении, когда приложение
     * в фоне (в foreground UI и так показывает сообщение в списке).
     * Можно менять в рантайме через [setNotificationsEnabled].
     */
    notifyInBackground: Boolean = true,
) {

    companion object {
        private const val TAG = "MeshChatSdk"
        private const val GOSSIP_OWNER = "MeshChatSdk"
    }

    /** Текущее состояние пуш-уведомлений о входящих (изменяемое). */
    @Volatile
    private var notifyInBackground: Boolean = notifyInBackground

    /** Включить/выключить локальные уведомления о входящих сообщениях. */
    fun setNotificationsEnabled(enabled: Boolean) {
        notifyInBackground = enabled
        Log.i(TAG, "notifyInBackground=$enabled")
    }

    private val appContext = context.applicationContext

    @Volatile
    private var started = false

    /** В фоне или нет — для решения, слать ли локальное уведомление. */
    @Volatile
    private var appInForeground = true

    init {
        // Включаем персистентность настроек/идентичности (best-effort).
        try { DebugPreferenceManager.init(appContext) } catch (_: Exception) {}
        nickname?.let { NicknameProvider.setNickname(it) }

        // Следим за foreground/background всего процесса (на главном потоке).
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

    // Постоянное хранилище доверенных (verified) отпечатков — тот же EncryptedSharedPreferences,
    // что использует mesh-ядро (читает/пишет ключ verified_fingerprints).
    private val identityStore = SecureIdentityStateManager(appContext)

    init {
        // Сообщаем наверх, когда установлена Noise-сессия с пиром (для иконки 🔒).
        meshService.setOnNoiseSessionEstablished { peerID ->
            Log.i(TAG, "🔒 Noise session established with $peerID")
            listener.onNoiseSession(peerID, true)
        }
        meshService.delegate = object : BluetoothMeshDelegate {
            override fun didReceiveMessage(message: BitchatMessage) {
                Log.i(TAG, "📥 RX message from=${message.senderPeerID} private=${message.isPrivate} " +
                    "relay=${message.isRelay} content='${message.content.take(60)}'")
                listener.onMessageReceived(message.toMeshChatMessage())
                // В фоне JS спит — показываем нативное уведомление о входящем.
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

            // Каналы с паролем — это фаза 2 (вместе с шифрованием), пока не расшифровываем.
            override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? = null

            override fun getNickname(): String? =
                NicknameProvider.getNickname(appContext, meshService.myPeerID)

            override fun isFavorite(peerID: String): Boolean = false
        }
    }

    /** Идентификатор этого узла в mesh-сети (стабильный, привязан к ключу). */
    val myPeerID: String get() = meshService.myPeerID

    fun setNickname(nickname: String?) {
        NicknameProvider.setNickname(nickname)
    }

    /**
     * Режим экономии батареи. При включении mesh в фоне работает реже
     * (POWER_SAVER): меньше расход, но доставка в фоне менее оперативна.
     * По умолчанию выключен (в фоне держим BALANCED).
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
            // Сразу анонсируем себя, чтобы соседние узлы нас увидели.
            meshService.sendBroadcastAnnounce()
            // Foreground-сервис: держим процесс живым в фоне.
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

    /** Короткая сводка состояния транспорта — для диагностики. */
    private fun transportStatus(): String = try {
        "activePeers=${meshService.getActivePeerCount()} peers=${meshService.getPeerNicknames().keys}"
    } catch (t: Throwable) {
        "status unavailable: ${t.message}"
    }

    /** Полный отладочный статус транспорта (можно дёргать из RN). */
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

    /** Широковещательное сообщение всем узлам сети. */
    fun sendBroadcastMessage(text: String) {
        Log.i(TAG, "📤 sendBroadcast len=${text.length} started=$started ${transportStatus()}")
        if (!ensureStarted()) return
        try {
            meshService.sendMessage(text)
            Log.i(TAG, "📤 sendBroadcast -> meshService.sendMessage() returned ok")
            // Локальное эхо: транспорт только передаёт пакет в сеть и не возвращает
            // собственное сообщение через didReceiveMessage, поэтому показываем его
            // отправителю сами — иначе кажется, что «сообщение не отправилось».
            echoOwnMessage(text, isPrivate = false, recipientNickname = null)
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Failed to send broadcast message", t)
            listener.onError(t)
        }
    }

    /** Приватное сообщение конкретному узлу по его peerID. */
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

    // --- Сквозное шифрование (Noise) и верификация пиров ---

    /**
     * Инициировать Noise-handshake с пиром. Нужно вызвать перед первой отправкой
     * приватного сообщения: `sendPrivateMessage` работает по принципу fire-and-forget
     * и, если сессии ещё нет, сообщение НЕ отправится (только запустится handshake).
     * Об установлении сессии придёт onNoiseSession(peerID, true).
     */
    fun startHandshake(peerID: String) {
        if (!ensureStarted()) return
        Log.i(TAG, "🤝 startHandshake with $peerID")
        meshService.initiateNoiseHandshake(peerID)
    }

    /** Есть ли установленная (зашифрованная) Noise-сессия с пиром. */
    fun hasSession(peerID: String): Boolean = try {
        meshService.hasEstablishedSession(peerID)
    } catch (_: Exception) { false }

    /** Отпечаток ключа пира (SHA-256 статического Noise-ключа) — для сверки вне сети. */
    fun getPeerFingerprint(peerID: String): String? = try {
        meshService.getPeerFingerprint(peerID)
    } catch (_: Exception) { null }

    /** Наш собственный отпечаток ключа (его сверяет собеседник). */
    fun getMyFingerprint(): String? = try {
        meshService.getIdentityFingerprint()
    } catch (_: Exception) { null }

    /** Помечен ли пир как доверенный (verified) — по отпечатку, хранится локально. */
    fun isPeerVerified(peerID: String): Boolean {
        return try {
            val fp = meshService.getPeerFingerprint(peerID) ?: return false
            identityStore.isVerifiedFingerprint(fp)
        } catch (_: Exception) {
            false
        }
    }

    /** Пометить/снять пир как доверенный (после сверки отпечатков вне сети). */
    fun setPeerVerified(peerID: String, verified: Boolean) {
        try {
            val fp = meshService.getPeerFingerprint(peerID)
            if (fp == null) {
                Log.w(TAG, "setPeerVerified: нет отпечатка для $peerID (сессия не установлена?)")
                return
            }
            identityStore.setVerifiedFingerprint(fp, verified)
            Log.i(TAG, "🛡️ peer $peerID verified=$verified (fp=${fp.take(16)}…)")
        } catch (t: Throwable) {
            Log.e(TAG, "setPeerVerified failed", t)
        }
    }

    /** Никнейм пира по его peerID (или null, если неизвестен). */
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
