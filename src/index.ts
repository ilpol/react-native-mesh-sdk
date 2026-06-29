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

/** Событие установления/сброса сквозной Noise-сессии с пиром. */
export type MeshChatNoiseSession = {
  peerID: string;
  established: boolean;
};

const emitter = new NativeEventEmitter(MeshChat);

/**
 * Запускает mesh-транспорт. Возвращает peerID этого узла.
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
 * Включить/выключить локальные уведомления о входящих сообщениях, когда
 * приложение в фоне. По умолчанию включены. Не влияет на постоянное уведомление
 * foreground-сервиса на Android (оно обязательно для фоновой работы).
 */
export function setNotificationsEnabled(enabled: boolean): Promise<void> {
  if (typeof MeshChat?.setNotificationsEnabled !== 'function') {
    return Promise.resolve();
  }
  return MeshChat.setNotificationsEnabled(enabled);
}

/**
 * Режим экономии батареи. При включении mesh в фоне работает реже
 * (меньше расход, но фоновая доставка менее оперативна). По умолчанию выкл.
 * Реальный эффект — на Android; на iOS фоновый BLE троттлит сама система.
 */
export function setBatterySaver(enabled: boolean): Promise<void> {
  // Защита от устаревшего нативного бинарника (нужен ребилд после добавления метода).
  if (typeof MeshChat?.setBatterySaver !== 'function') {
    console.warn(
      'MeshChat.setBatterySaver недоступен — пересоберите нативное приложение (не просто reload JS).',
    );
    return Promise.resolve();
  }
  return MeshChat.setBatterySaver(enabled);
}

/**
 * Исключено ли приложение из оптимизации батареи (Android).
 * На iOS всегда true (нет такого механизма).
 */
export function isIgnoringBatteryOptimizations(): Promise<boolean> {
  if (Platform.OS !== 'android' || typeof MeshChat?.isIgnoringBatteryOptimizations !== 'function') {
    return Promise.resolve(true);
  }
  return MeshChat.isIgnoringBatteryOptimizations();
}

/**
 * Показать системный диалог «работать без ограничений батареи» (Android).
 * Резко повышает живучесть фона/после свайпа на агрессивных прошивках.
 */
export function requestIgnoreBatteryOptimizations(): Promise<void> {
  if (Platform.OS !== 'android' || typeof MeshChat?.requestIgnoreBatteryOptimizations !== 'function') {
    return Promise.resolve();
  }
  return MeshChat.requestIgnoreBatteryOptimizations();
}

/** Широковещательное сообщение всем узлам сети. */
export function sendBroadcast(text: string): Promise<void> {
  return MeshChat.sendBroadcast(text);
}

/** Приватное сообщение конкретному узлу по его peerID. */
export function sendPrivate(
  text: string,
  recipientPeerID: string,
  recipientNickname: string,
): Promise<void> {
  return MeshChat.sendPrivate(text, recipientPeerID, recipientNickname);
}

// --- Сквозное шифрование (Noise) и верификация пиров ---
//
// Приватные сообщения шифруются end-to-end по протоколу Noise XX (как в bitchat):
// перед первой отправкой нужно установить сессию (`startHandshake`), затем дождаться
// события `subscribeNoiseSessions` (established=true) и только потом слать `sendPrivate`
// — иначе сообщение не уйдёт (транспорт fire-and-forget). Broadcast не шифруется.

/**
 * Инициировать Noise-handshake с пиром (нужно перед первым приватным сообщением).
 * Об установлении сессии придёт событие через `subscribeNoiseSessions`.
 */
export function startHandshake(peerID: string): Promise<void> {
  if (typeof MeshChat?.startHandshake !== 'function') {
    console.warn('MeshChat.startHandshake недоступен — пересоберите нативное приложение.');
    return Promise.resolve();
  }
  return MeshChat.startHandshake(peerID);
}

/** Установлена ли зашифрованная Noise-сессия с пиром. */
export function hasSession(peerID: string): Promise<boolean> {
  if (typeof MeshChat?.hasSession !== 'function') {
    return Promise.resolve(false);
  }
  return MeshChat.hasSession(peerID);
}

/** Отпечаток ключа пира (SHA-256) — для сверки «вживую»; null, пока нет сессии. */
export function getPeerFingerprint(peerID: string): Promise<string | null> {
  if (typeof MeshChat?.getPeerFingerprint !== 'function') {
    return Promise.resolve(null);
  }
  return MeshChat.getPeerFingerprint(peerID);
}

/** Наш собственный отпечаток ключа (его сверяет собеседник). */
export function getMyFingerprint(): Promise<string | null> {
  if (typeof MeshChat?.getMyFingerprint !== 'function') {
    return Promise.resolve(null);
  }
  return MeshChat.getMyFingerprint();
}

/** Помечен ли пир как доверенный (verified). */
export function isPeerVerified(peerID: string): Promise<boolean> {
  if (typeof MeshChat?.isPeerVerified !== 'function') {
    return Promise.resolve(false);
  }
  return MeshChat.isPeerVerified(peerID);
}

/** Пометить/снять пир доверенным (после сверки отпечатков вне сети). */
export function setPeerVerified(peerID: string, verified: boolean): Promise<void> {
  if (typeof MeshChat?.setPeerVerified !== 'function') {
    return Promise.resolve();
  }
  return MeshChat.setPeerVerified(peerID, verified);
}

/** Никнейм пира по peerID (или null). */
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
    // Обязательные BLE-разрешения.
    const required: string[] =
      sdkInt >= 12
        ? [
            'android.permission.BLUETOOTH_SCAN',
            'android.permission.BLUETOOTH_CONNECT',
            'android.permission.BLUETOOTH_ADVERTISE',
          ]
        : ['android.permission.ACCESS_FINE_LOCATION'];

    // POST_NOTIFICATIONS (Android 13+) — для уведомления foreground-сервиса.
    // Не обязателен: без него mesh работает, просто не видно уведомления.
    const optional: string[] =
      sdkInt >= 13 ? ['android.permission.POST_NOTIFICATIONS'] : [];

    const results = await PermissionsAndroid.requestMultiple([
      ...required,
      ...optional,
    ] as any);

    // Успех определяем только по обязательным разрешениям.
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

/** Подписка на установление сквозной Noise-сессии с пирами (иконка 🔒). */
export function subscribeNoiseSessions(
  cb: (session: MeshChatNoiseSession) => void,
): () => void {
  const sub = emitter.addListener('MeshChatNoiseSession', cb);
  return () => sub.remove();
}
