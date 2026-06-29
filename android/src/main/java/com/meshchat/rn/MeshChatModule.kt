package com.meshchat.rn

import com.example.meshchat.sdk.MeshChatListener
import com.example.meshchat.sdk.MeshChatMessage
import com.example.meshchat.sdk.MeshChatSdk
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule

class MeshChatModule(
    private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext), MeshChatListener {

    private var sdk: MeshChatSdk? = null

    override fun getName(): String = "MeshChat"

    // region React methods

    @ReactMethod
    fun start(nickname: String?, promise: Promise) {
        try {
            val instance = sdk ?: MeshChatSdk(reactContext, this, nickname).also { sdk = it }
            instance.start()
            promise.resolve(instance.myPeerID)
        } catch (t: Throwable) {
            promise.reject("START_FAILED", t.message, t)
        }
    }

    @ReactMethod
    fun stop(promise: Promise) {
        sdk?.stop()
        // IMPORTANT: after stop() the mesh instance is terminated and not reused.
        // Null it out so the next start() creates a fresh SDK (otherwise mesh won't
        // start after the app is closed/swiped away and reopened).
        sdk = null
        promise.resolve(null)
    }

    @ReactMethod
    fun setNickname(nickname: String?, promise: Promise) {
        sdk?.setNickname(nickname)
        promise.resolve(null)
    }

    @ReactMethod
    fun getMyPeerID(promise: Promise) {
        promise.resolve(sdk?.myPeerID)
    }

    @ReactMethod
    fun getDebugStatus(promise: Promise) {
        promise.resolve(sdk?.debugStatus() ?: "sdk not created")
    }

    @ReactMethod
    fun setBatterySaver(enabled: Boolean, promise: Promise) {
        sdk?.setBatterySaver(enabled)
        promise.resolve(null)
    }

    @ReactMethod
    fun setNotificationsEnabled(enabled: Boolean, promise: Promise) {
        sdk?.setNotificationsEnabled(enabled)
        promise.resolve(null)
    }

    @ReactMethod
    fun isIgnoringBatteryOptimizations(promise: Promise) {
        promise.resolve(com.example.meshchat.sdk.MeshBattery.isIgnoringBatteryOptimizations(reactContext))
    }

    @ReactMethod
    fun requestIgnoreBatteryOptimizations(promise: Promise) {
        val activity = currentActivity ?: reactContext
        com.example.meshchat.sdk.MeshBattery.requestIgnoreBatteryOptimizations(activity)
        promise.resolve(null)
    }

    @ReactMethod
    fun sendBroadcast(text: String, promise: Promise) {
        val instance = sdk
        if (instance == null) {
            promise.reject("NOT_STARTED", "MeshChatSdk is not started")
            return
        }
        instance.sendBroadcastMessage(text)
        promise.resolve(null)
    }

    @ReactMethod
    fun sendPrivate(text: String, recipientPeerID: String, recipientNickname: String, promise: Promise) {
        val instance = sdk
        if (instance == null) {
            promise.reject("NOT_STARTED", "MeshChatSdk is not started")
            return
        }
        instance.sendPrivateMessage(text, recipientPeerID, recipientNickname)
        promise.resolve(null)
    }

    // region Encryption (Noise) and verification

    @ReactMethod
    fun startHandshake(peerID: String, promise: Promise) {
        sdk?.startHandshake(peerID)
        promise.resolve(null)
    }

    @ReactMethod
    fun hasSession(peerID: String, promise: Promise) {
        promise.resolve(sdk?.hasSession(peerID) ?: false)
    }

    @ReactMethod
    fun getPeerFingerprint(peerID: String, promise: Promise) {
        promise.resolve(sdk?.getPeerFingerprint(peerID))
    }

    @ReactMethod
    fun getMyFingerprint(promise: Promise) {
        promise.resolve(sdk?.getMyFingerprint())
    }

    @ReactMethod
    fun isPeerVerified(peerID: String, promise: Promise) {
        promise.resolve(sdk?.isPeerVerified(peerID) ?: false)
    }

    @ReactMethod
    fun setPeerVerified(peerID: String, verified: Boolean, promise: Promise) {
        sdk?.setPeerVerified(peerID, verified)
        promise.resolve(null)
    }

    @ReactMethod
    fun getPeerNickname(peerID: String, promise: Promise) {
        promise.resolve(sdk?.getPeerNickname(peerID))
    }

    // endregion

    // Required for NativeEventEmitter in the New Architecture (bridgeless): without them
    // JS subscriptions don't receive events from native. The actual delivery goes through
    // RCTDeviceEventEmitter (see sendEvent), so this is a no-op.
    @ReactMethod
    fun addListener(eventName: String) {
        // no-op
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // no-op
    }

    // endregion

    // region MeshChatListener

    override fun onMessageReceived(message: MeshChatMessage) {
        val map = Arguments.createMap().apply {
            putString("id", message.id)
            message.senderPeerID?.let { putString("senderPeerID", it) }
            putString("sender", message.sender)
            putString("content", message.content)
            putDouble("timestamp", message.timestamp.toDouble())
            putBoolean("isPrivate", message.isPrivate)
            message.channel?.let { putString("channel", it) }
            putBoolean("isRelay", message.isRelay)
        }
        sendEvent("MeshChatMessage", map)
    }

    override fun onNoiseSession(peerID: String, established: Boolean) {
        val map = Arguments.createMap().apply {
            putString("peerID", peerID)
            putBoolean("established", established)
        }
        sendEvent("MeshChatNoiseSession", map)
    }

    override fun onPeerListUpdated(peers: List<String>) {
        val arr = Arguments.createArray()
        peers.forEach { arr.pushString(it) }
        val map = Arguments.createMap().apply { putArray("peers", arr) }
        sendEvent("MeshChatPeers", map)
    }

    override fun onDeliveryAck(messageID: String, peerID: String) {
        val map = Arguments.createMap().apply {
            putString("messageID", messageID)
            putString("peerID", peerID)
        }
        sendEvent("MeshChatDeliveryAck", map)
    }

    override fun onReadReceipt(messageID: String, peerID: String) {
        val map = Arguments.createMap().apply {
            putString("messageID", messageID)
            putString("peerID", peerID)
        }
        sendEvent("MeshChatReadReceipt", map)
    }

    override fun onError(error: Throwable) {
        val map = Arguments.createMap().apply {
            putString("error", error.message)
        }
        sendEvent("MeshChatError", map)
    }

    override fun onLog(message: String) {
        val map = Arguments.createMap().apply {
            putString("message", message)
        }
        sendEvent("MeshChatLog", map)
    }

    // endregion

    private fun sendEvent(eventName: String, params: WritableMap?) {
        try {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit(eventName, params)
        } catch (t: Throwable) {
            android.util.Log.e("MeshChatModule", "sendEvent '$eventName' failed", t)
        }
    }
}
