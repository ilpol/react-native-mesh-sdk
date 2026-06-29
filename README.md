# react-native-mesh-sdk

Offline chat over a **Bluetooth LE mesh network** for React Native (iOS + Android).
No internet, servers, or accounts: messages travel directly between nearby devices and
are relayed across the network (multi-hop). The native transport is ported from
[bitchat](https://github.com/permissionlesstech/bitchat); iOS and Android are
protocol-compatible.

## Installation

> ⚠️ **Not published to the npm registry yet.** So `npm install react-native-mesh-sdk`
> from the registry won't work — link it locally as a `file:` dependency first.
> 1. Copy the `react-native-mesh-sdk/` directory next to your project
>    (e.g. one level up — `../react-native-mesh-sdk`).
> 2. Add it to your project's `package.json` under `dependencies`:
>    ```json
>    "react-native-mesh-sdk": "file:../react-native-mesh-sdk"
>    ```
>    (the path is relative to your `package.json`; adjust to your layout).

```sh
npm install            # pulls the package via the file: path
# iOS:
cd ios && pod install
```

That's it. The native code (Android library and iOS pod) is wired up automatically via
**React Native autolinking** — no manual Gradle/CocoaPods/bridge setup needed.

> Once published to the registry, installation simplifies to `npm install react-native-mesh-sdk`
> (the `file:…` line in `package.json` is no longer needed).

> Requirements: React Native 0.71+, **iOS 16+**, **Android minSdk 26+**.

### Permissions

The package already declares the required Android permissions (BLE, foreground service,
notifications) in its manifest — they merge into your app. On iOS, add to `Info.plist`:

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>Used for Bluetooth mesh chat.</string>
<key>UIBackgroundModes</key>
<array>
  <string>bluetooth-central</string>
  <string>bluetooth-peripheral</string>
</array>
```

## Usage

```ts
import {
  ensureBlePermissions, startMesh, stopMesh,
  sendBroadcast, sendPrivate, setBatterySaver,
  subscribeMessages, subscribePeers, subscribeErrors,
} from 'react-native-mesh-sdk';

await ensureBlePermissions();              // Android: request BLE permissions
const myPeerID = await startMesh('Alice'); // start the transport, returns your peerID

const unsub = subscribeMessages(m => console.log(m.sender, m.content));
subscribePeers(peers => console.log('online:', peers));

await sendBroadcast('hello everyone');   // public chat — to everyone, NOT encrypted
// private (encrypted) message — see the "Encryption" section below,
// it cannot be sent until a Noise session is established:
// await sendPrivate('private', peerID, 'RecipientNick');

await setBatterySaver(true);   // background battery saving (off by default)

unsub();
await stopMesh();
```

Message type: `{ id, senderPeerID?, sender, content, timestamp, isPrivate, isRelay }`.

## Encryption and private chats

Two message models (like bitchat):

| | Public chat (`sendBroadcast`) | Private chat (`sendPrivate`) |
|---|---|---|
| To whom | everyone on the network | a single peer by `peerID` |
| Encryption | none (plaintext) | **end-to-end, Noise XX** |
| Readiness | immediate | a Noise session is required first |

**Important:** `sendPrivate` is fire-and-forget — if an end-to-end session with the peer is
not established yet, the message is **silently dropped**. So the flow is: initiate the
handshake → wait for the session event → only then send.

```ts
import {
  startHandshake, hasSession, sendPrivate,
  subscribeNoiseSessions, subscribeMessages,
} from 'react-native-mesh-sdk';

// 1. initiate an end-to-end session with the peer
await startHandshake(peerID);

// 2. wait for it to be established (🔒)
const unsub = subscribeNoiseSessions(({ peerID: p, established }) => {
  if (p === peerID && established) {
    // 3. now the message goes out encrypted
    sendPrivate('secret', peerID, nickname);
  }
});

// receiving: private messages arrive with isPrivate === true
subscribeMessages(m => {
  if (m.isPrivate && m.senderPeerID === peerID) { /* DM from them */ }
});
```

### Peer verification — what "Verified" means (like the "lock" in bitchat)

Encryption (🔒) guarantees that **nobody in transit can read** your messages, but on its
own it does **not prove WHO** is on the other end. A man-in-the-middle (MITM) attack is
theoretically possible: an attacker sits between you, keeps a separate encrypted session
with each side, and reads everything. The defense is **comparing key fingerprints**.

A fingerprint is a unique "imprint" of a device's key; for the real peer it's identical on
both phones. Procedure:

1. In the DM each side shows two fingerprints — **"Yours"** and **"Peer"**.
2. Compare them over an **independent channel** (in person, by voice, by video call — NOT
   through this same chat): on your screen "Peer" must match what they see as "Yours", and
   vice versa.
3. If they match → flip the **"Verified"** toggle (✅) = "I personally confirmed it's really them".

Notes:
- The toggle **does not affect encryption** — messages are encrypted regardless once the
  session is established. "Verified" is only a trust marker/indicator (like the lock-shield
  in bitchat).
- The mark is **stored locally and survives restarts** (tied to the peer's key fingerprint,
  not the session) — you only verify once.
- If a peer's fingerprint **stops matching** — that's a red flag (key change/reinstall, or
  MITM): remove trust and re-verify.

In short: 🔒 = "nobody can eavesdrop", ✅ "Verified" = "confirmed it's that exact person".

```ts
import { getMyFingerprint, getPeerFingerprint, isPeerVerified, setPeerVerified } from 'react-native-mesh-sdk';

const mine   = await getMyFingerprint();         // your fingerprint (the peer compares it)
const theirs = await getPeerFingerprint(peerID); // peer fingerprint (available after 🔒)
if (await isPeerVerified(peerID)) { /* already verified */ }
await setPeerVerified(peerID, true);             // mark verified after comparing
```

Helper methods: `hasSession(peerID)` — whether a session already exists; `getPeerNickname(peerID)` —
a peer's nickname by id. All encryption methods no-op (rather than throw) on a stale native
binary — after adding new methods you need a full app rebuild, not just a JS reload.

## Features
- Reliable delivery (GATT connections, fragmentation, store-and-forward, TTL relay, dedup).
- Cross-platform mesh iOS ↔ Android (shared private BLE service UUID).
- **End-to-end encryption for private messages (Noise XX) + peer verification by fingerprint.**
- Background operation (Android foreground service; iOS background BLE + restoration).
- Local push notifications for incoming messages while the app is backgrounded.
- Battery-saver toggle.

## License

**GPL-3.0-or-later.** This package is a derivative work: the Android transport is vendored
from [bitchat-android](https://github.com/permissionlesstech/bitchat-android) (GPL v3), so
the whole package is distributed under GPL v3. This is "strong copyleft": an app that
includes this package generally must also be open-sourced under the GPL.

The iOS transport is vendored from [bitchat (iOS)](https://github.com/permissionlesstech/bitchat)
(The Unlicense, public domain); the Noise primitives are from noise-java/southernstorm (MIT).
Full attribution and texts are in the [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE) files.
