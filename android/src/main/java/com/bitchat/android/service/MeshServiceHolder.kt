package com.bitchat.android.service

import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.sync.GossipSyncManager

/**
 * SDK trim of bitchat's MeshServiceHolder.
 *
 * The original also owned process-wide singletons of BluetoothMeshService /
 * UnifiedMeshService (the latter drags in the Nostr transport, which the SDK
 * drops). Here we keep only the shared GossipSyncManager lifecycle, which the
 * mesh transport wires up for store-and-sync of recently seen packets.
 */
object MeshServiceHolder {

    @Volatile
    private var sharedGossipSyncManager: GossipSyncManager? = null
    private val activeGossipOwners = mutableSetOf<String>()

    /**
     * The active mesh service instance. The SDK registers its [BluetoothMeshService]
     * here so app-level toggles (e.g. the debug BLE switch) can reach it. Null until
     * the SDK starts the transport.
     */
    @Volatile
    var meshService: BluetoothMeshService? = null

    @Synchronized
    fun setGossipManager(
        mgr: GossipSyncManager,
        signer: (BitchatPacket) -> BitchatPacket
    ) {
        val previous = sharedGossipSyncManager
        if (previous !== mgr) {
            try { previous?.stop() } catch (_: Exception) {}
        }
        sharedGossipSyncManager = mgr
        mgr.delegate = TransportGossipDelegate(signer)
        if (activeGossipOwners.isNotEmpty()) {
            mgr.start()
        }
    }

    @Synchronized
    fun startSharedGossip(owner: String) {
        val wasIdle = activeGossipOwners.isEmpty()
        activeGossipOwners.add(owner)
        if (wasIdle) {
            sharedGossipSyncManager?.start()
        }
    }

    @Synchronized
    fun stopSharedGossip(owner: String) {
        activeGossipOwners.remove(owner)
        if (activeGossipOwners.isEmpty()) {
            sharedGossipSyncManager?.stop()
        }
    }

    private class TransportGossipDelegate(
        private val signer: (BitchatPacket) -> BitchatPacket
    ) : GossipSyncManager.Delegate {
        override fun sendPacket(packet: BitchatPacket) {
            TransportBridgeService.broadcastFromLocal(RoutedPacket(packet))
        }

        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
            TransportBridgeService.sendToPeerFromLocal(peerID, packet)
        }

        override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket {
            return signer(packet)
        }
    }
}
