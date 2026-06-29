package com.meshchat.rn

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ViewManager

class MeshChatPackage : ReactPackage {
    override fun createNativeModules(
        reactContext: ReactApplicationContext,
    ): MutableList<NativeModule> =
        mutableListOf(MeshChatModule(reactContext))

    override fun createViewManagers(
        reactContext: ReactApplicationContext,
    ): MutableList<ViewManager<*, *>> = mutableListOf()
}

