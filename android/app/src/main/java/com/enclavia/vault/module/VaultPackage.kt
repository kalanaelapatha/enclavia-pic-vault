package com.enclavia.vault.module

import com.enclavia.vault.specs.NativeVaultSpec
import com.enclavia.vault.ui.VaultImageViewManager
import com.enclavia.vault.ui.VaultVideoViewManager
import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.facebook.react.uimanager.ViewManager

class VaultPackage : BaseReactPackage() {

  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? =
    if (name == NativeVaultSpec.NAME) VaultModule(reactContext) else null

  override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
    mapOf(
      NativeVaultSpec.NAME to ReactModuleInfo(
        name = NativeVaultSpec.NAME,
        className = VaultModule::class.java.name,
        canOverrideExistingModule = false,
        needsEagerInit = true,
        isCxxModule = false,
        isTurboModule = true,
      )
    )
  }

  override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> =
    listOf(VaultImageViewManager(), VaultVideoViewManager())
}
