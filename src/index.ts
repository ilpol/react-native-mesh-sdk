import {
  NativeEventEmitter,
  NativeModules,
  PermissionsAndroid,
  Platform,
} from 'react-native';

const {MeshChat} = NativeModules;

export type MeshChatMessage = {
  id: string;
  senderPeerID?: string;
  sender: string;
  content: string;
  timestamp: number;
  isPrivate: boolean;
  channel?: string;
  isRelay: boolean;
};

export type MeshChatDeliveryAck = {
  messageID: string;
  peerID: string;
};

export type MeshChatError = {
  error: string;
};

/** Event for establishing/tearing down an end-to-end Noise session with a peer. */
export type MeshChatNoiseSession = {
  peerID: string;
  established: boolean;
};

const emitter = new NativeEventEmitter(MeshChat);

/**
 * Starts the mesh transport. Returns this node's peerID.
 */
export function startMesh(nickname?: string): Promise<string> {
  return MeshChat.start(nickname ?? null);
}

export function stopMesh(): Promise<void> {
  return MeshChat.stop();
}

export function setNickname(nickname?: string): Promise<void> {
  return MeshChat.setNickname(nickname ?? null);
}

export function getMyPeerID(): Promise<string | null> {
  return MeshChat.getMyPeerID();
}

/**
 * Enable/disable local notifications for incoming messages while the app is
 * backgrounded. Enabled by default. Does not affect the persistent foreground-service
 * notification on Android (which is mandatory for background operation).
 */
export function setNotificationsEnabled(enabled: boolean): Promise<void> {
  if (typeof MeshChat?.setNotificationsEnabled !== 'function') {
    return Promise.resolve();
  }
  return MeshChat.setNotificationsEnabled(enabled);
}

/**
 * Battery saver mode. When enabled, mesh runs less often in the background
 * (lower drain, but background delivery is less prompt). Off by default.
 * The real effect is on Android; on iOS background BLE is throttled by the system itself.
 */
export function setBatterySaver(enabled: boolean): Promise<void> {
  // Guard against a stale native binary (a rebuild is needed after adding the method).
  if (typeof MeshChat?.setBatterySaver !== 'function') {
    console.warn(
      'MeshChat.setBatterySaver is unavailable — rebuild the native app (not just a JS reload).',
    );
    return Promise.resolve();
  }
  return MeshChat.setBatterySaver(enabled);
}

/**
 * Whether the app is exempt from battery optimization (Android).
 * On iOS always true (there's no such mechanism).
 */
export function isIgnoringBatteryOptimizations(): Promise<boolean> {
  if (Platform.OS !== 'android' || typeof MeshChat?.isIgnoringBatteryOptimizations !== 'function') {
    return Promise.resolve(true);
  }
  return MeshChat.isIgnoringBatteryOptimizations();
}

/**
 * Show the system "run without battery restrictions" dialog (Android).
 * Dramatically improves background/after-swipe survivability on aggressive firmware.
 */
export function requestIgnoreBatteryOptimizations(): Promise<void> {
  if (Platform.OS !== 'android' || typeof MeshChat?.requestIgnoreBatteryOptimizations !== 'function') {
    return Promise.resolve();
  }
  return MeshChat.requestIgnoreBatteryOptimizations();
}

/** Broadcast message to all nodes on the network. */
export function sendBroadcast(text: string): Promise<void> {
  return MeshChat.sendBroadcast(text);
}

/** Private message to a specific node by its peerID. */
export function sendPrivate(
  text: string,
  recipientPeerID: string,
  recipientNickname: string,
): Promise<void> {
  return MeshChat.sendPrivate(text, recipientPeerID, recipientNickname);
}

// --- End-to-end encryption (Noise) and peer verification ---
//
// Private messages are encrypted end-to-end with the Noise XX protocol (as in bitchat):
// before the first send you must establish a session (`startHandshake`), then wait for
// the `subscribeNoiseSessions` event (established=true), and only then send `sendPrivate`
// — otherwise the message won't go out (the transport is fire-and-forget). Broadcast is not encrypted.

/**
 * Initiate a Noise handshake with a peer (needed before the first private message).
 * Session establishment is reported via the `subscribeNoiseSessions` event.
 */
export function startHandshake(peerID: string): Promise<void> {
  if (typeof MeshChat?.startHandshake !== 'function') {
    console.warn('MeshChat.startHandshake is unavailable — rebuild the native app.');
    return Promise.resolve();
  }
  return MeshChat.startHandshake(peerID);
}

/** Whether an encrypted Noise session is established with the peer. */
export function hasSession(peerID: string): Promise<boolean> {
  if (typeof MeshChat?.hasSession !== 'function') {
    return Promise.resolve(false);
  }
  return MeshChat.hasSession(peerID);
}

/** Peer's key fingerprint (SHA-256) — for in-person comparison; null until there's a session. */
export function getPeerFingerprint(peerID: string): Promise<string | null> {
  if (typeof MeshChat?.getPeerFingerprint !== 'function') {
    return Promise.resolve(null);
  }
  return MeshChat.getPeerFingerprint(peerID);
}

/** Our own key fingerprint (the other party compares it). */
export function getMyFingerprint(): Promise<string | null> {
  if (typeof MeshChat?.getMyFingerprint !== 'function') {
    return Promise.resolve(null);
  }
  return MeshChat.getMyFingerprint();
}

/** Whether the peer is marked verified. */
export function isPeerVerified(peerID: string): Promise<boolean> {
  if (typeof MeshChat?.isPeerVerified !== 'function') {
    return Promise.resolve(false);
  }
  return MeshChat.isPeerVerified(peerID);
}

/** Mark/unmark a peer as verified (after comparing fingerprints out of band). */
export function setPeerVerified(peerID: string, verified: boolean): Promise<void> {
  if (typeof MeshChat?.setPeerVerified !== 'function') {
    return Promise.resolve();
  }
  return MeshChat.setPeerVerified(peerID, verified);
}

/** Peer's nickname by peerID (or null). */
export function getPeerNickname(peerID: string): Promise<string | null> {
  if (typeof MeshChat?.getPeerNickname !== 'function') {
    return Promise.resolve(null);
  }
  return MeshChat.getPeerNickname(peerID);
}

export async function ensureBlePermissions(): Promise<boolean> {
  if (Platform.OS !== 'android') {
    return true;
  }

  const sdkInt = Number(Platform.constants?.Release ?? 0);

  try {
    // Mandatory BLE permissions.
    const required: string[] =
      sdkInt >= 12
        ? [
            'android.permission.BLUETOOTH_SCAN',
            'android.permission.BLUETOOTH_CONNECT',
            'android.permission.BLUETOOTH_ADVERTISE',
          ]
        : ['android.permission.ACCESS_FINE_LOCATION'];

    // POST_NOTIFICATIONS (Android 13+) — for the foreground-service notification.
    // Not mandatory: without it mesh still works, the notification just isn't visible.
    const optional: string[] =
      sdkInt >= 13 ? ['android.permission.POST_NOTIFICATIONS'] : [];

    const results = await PermissionsAndroid.requestMultiple([
      ...required,
      ...optional,
    ] as any);

    // Determine success only by the mandatory permissions.
    return required.every(
      p => (results as any)[p] === PermissionsAndroid.RESULTS.GRANTED,
    );
  } catch {
    return false;
  }
}

export function subscribeMessages(
  cb: (msg: MeshChatMessage) => void,
): () => void {
  const sub = emitter.addListener('MeshChatMessage', cb);
  return () => sub.remove();
}

export function subscribePeers(cb: (peers: string[]) => void): () => void {
  const sub = emitter.addListener('MeshChatPeers', (e: {peers: string[]}) =>
    cb(e.peers),
  );
  return () => sub.remove();
}

export function subscribeDeliveryAcks(
  cb: (ack: MeshChatDeliveryAck) => void,
): () => void {
  const sub = emitter.addListener('MeshChatDeliveryAck', cb);
  return () => sub.remove();
}

export function subscribeReadReceipts(
  cb: (ack: MeshChatDeliveryAck) => void,
): () => void {
  const sub = emitter.addListener('MeshChatReadReceipt', cb);
  return () => sub.remove();
}

export function subscribeErrors(cb: (err: MeshChatError) => void): () => void {
  const sub = emitter.addListener('MeshChatError', cb);
  return () => sub.remove();
}

/** Subscribe to end-to-end Noise session establishment with peers (the 🔒 icon). */
export function subscribeNoiseSessions(
  cb: (session: MeshChatNoiseSession) => void,
): () => void {
  const sub = emitter.addListener('MeshChatNoiseSession', cb);
  return () => sub.remove();
}
