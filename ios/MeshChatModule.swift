import Foundation
import React

@objc(MeshChat)
final class MeshChatModule: RCTEventEmitter, MeshChatListener {

  private var sdk: MeshChatSdk?

  override static func moduleName() -> String! {
    return "MeshChat"
  }

  override static func requiresMainQueueSetup() -> Bool {
    return false
  }

  override func supportedEvents() -> [String]! {
    return ["MeshChatMessage", "MeshChatPeers", "MeshChatError", "MeshChatLog", "MeshChatNoiseSession"]
  }

  @objc
  func start(_ nickname: NSString?,
             resolver resolve: RCTPromiseResolveBlock,
             rejecter reject: RCTPromiseRejectBlock) {
    if sdk == nil {
      sdk = MeshChatSdk(listener: self, nickname: nickname as String?)
    }
    sdk?.start()
    resolve(sdk?.myPeerID)
  }

  @objc
  func stop(_ resolve: RCTPromiseResolveBlock,
            rejecter reject: RCTPromiseRejectBlock) {
    sdk?.stop()
    // Создаём свежий SDK при следующем start() (после закрытия/свайпа).
    sdk = nil
    resolve(nil)
  }

  @objc
  func setNickname(_ nickname: NSString?,
                   resolver resolve: RCTPromiseResolveBlock,
                   rejecter reject: RCTPromiseRejectBlock) {
    if let nickname = nickname as String? {
      sdk?.setNickname(nickname)
    }
    resolve(nil)
  }

  @objc
  func getMyPeerID(_ resolve: RCTPromiseResolveBlock,
                   rejecter reject: RCTPromiseRejectBlock) {
    resolve(sdk?.myPeerID)
  }

  @objc
  func getDebugStatus(_ resolve: RCTPromiseResolveBlock,
                      rejecter reject: RCTPromiseRejectBlock) {
    resolve(sdk?.debugStatus() ?? "sdk not created")
  }

  @objc
  func setBatterySaver(_ enabled: Bool,
                       resolver resolve: RCTPromiseResolveBlock,
                       rejecter reject: RCTPromiseRejectBlock) {
    sdk?.setBatterySaver(enabled)
    resolve(nil)
  }

  @objc
  func setNotificationsEnabled(_ enabled: Bool,
                               resolver resolve: RCTPromiseResolveBlock,
                               rejecter reject: RCTPromiseRejectBlock) {
    sdk?.setNotificationsEnabled(enabled)
    resolve(nil)
  }

  @objc
  func sendBroadcast(_ text: NSString,
                     resolver resolve: RCTPromiseResolveBlock,
                     rejecter reject: RCTPromiseRejectBlock) {
    guard let sdk = sdk else {
      reject("NOT_STARTED", "MeshChatSdk is not started", nil)
      return
    }
    sdk.sendBroadcastMessage(text as String)
    resolve(nil)
  }

  @objc
  func sendPrivate(_ text: NSString,
                   recipientPeerID: NSString,
                   recipientNickname: NSString,
                   resolver resolve: RCTPromiseResolveBlock,
                   rejecter reject: RCTPromiseRejectBlock) {
    guard let sdk = sdk else {
      reject("NOT_STARTED", "MeshChatSdk is not started", nil)
      return
    }
    sdk.sendPrivateMessage(text as String,
                           to: recipientPeerID as String,
                           recipientNickname: recipientNickname as String)
    resolve(nil)
  }

  // MARK: - Шифрование (Noise) и верификация

  @objc
  func startHandshake(_ peerID: NSString,
                      resolver resolve: RCTPromiseResolveBlock,
                      rejecter reject: RCTPromiseRejectBlock) {
    sdk?.startHandshake(peerID as String)
    resolve(nil)
  }

  @objc
  func hasSession(_ peerID: NSString,
                  resolver resolve: RCTPromiseResolveBlock,
                  rejecter reject: RCTPromiseRejectBlock) {
    resolve(sdk?.hasSession(peerID as String) ?? false)
  }

  @objc
  func getPeerFingerprint(_ peerID: NSString,
                          resolver resolve: RCTPromiseResolveBlock,
                          rejecter reject: RCTPromiseRejectBlock) {
    resolve(sdk?.getPeerFingerprint(peerID as String))
  }

  @objc
  func getMyFingerprint(_ resolve: RCTPromiseResolveBlock,
                        rejecter reject: RCTPromiseRejectBlock) {
    resolve(sdk?.getMyFingerprint())
  }

  @objc
  func isPeerVerified(_ peerID: NSString,
                      resolver resolve: RCTPromiseResolveBlock,
                      rejecter reject: RCTPromiseRejectBlock) {
    resolve(sdk?.isPeerVerified(peerID as String) ?? false)
  }

  @objc
  func setPeerVerified(_ peerID: NSString,
                       verified: Bool,
                       resolver resolve: RCTPromiseResolveBlock,
                       rejecter reject: RCTPromiseRejectBlock) {
    sdk?.setPeerVerified(peerID as String, verified)
    resolve(nil)
  }

  @objc
  func getPeerNickname(_ peerID: NSString,
                       resolver resolve: RCTPromiseResolveBlock,
                       rejecter reject: RCTPromiseRejectBlock) {
    resolve(sdk?.getPeerNickname(peerID as String))
  }

  // MARK: - MeshChatListener

  func onNoiseSession(_ peerID: String, established: Bool) {
    sendEvent(withName: "MeshChatNoiseSession", body: [
      "peerID": peerID,
      "established": established
    ])
  }

  func onMessageReceived(_ message: MeshChatMessage) {
    sendEvent(withName: "MeshChatMessage", body: [
      "id": message.id,
      "senderPeerID": message.senderPeerID as Any,
      "sender": message.sender,
      "content": message.content,
      "timestamp": message.timestamp,
      "isPrivate": message.isPrivate,
      "isRelay": message.isRelay
    ])
  }

  func onPeerListUpdated(_ peers: [String]) {
    sendEvent(withName: "MeshChatPeers", body: ["peers": peers])
  }

  func onError(_ error: Error) {
    sendEvent(withName: "MeshChatError", body: ["error": error.localizedDescription])
  }

  func onLog(_ message: String) {
    sendEvent(withName: "MeshChatLog", body: ["message": message])
  }
}
