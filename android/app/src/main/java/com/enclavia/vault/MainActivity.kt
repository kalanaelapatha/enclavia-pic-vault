package com.enclavia.vault

import android.os.Bundle
import android.view.WindowManager
import com.enclavia.vault.crypto.KeyManager
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

class MainActivity : ReactActivity() {

  override fun getMainComponentName(): String = "EnclaviaVault"

  override fun createReactActivityDelegate(): ReactActivityDelegate =
    DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

  override fun onCreate(savedInstanceState: Bundle?) {
    // Block screenshots, screen recording, casting and the recent-apps preview.
    if (!BuildConfig.ALLOW_SCREENSHOTS) {
      window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
    }
    // react-native-screens requires a null state (fragments are restored by React, not Android).
    super.onCreate(null)
    // Ignore touches while another app draws on top of us (tapjacking on the PIN pad).
    window.decorView.filterTouchesWhenObscured = true
  }

  override fun onStart() {
    super.onStart()
    KeyManager.get(this).onAppVisible()
  }

  override fun onStop() {
    // onStop (not onPause) so system dialogs such as permission or delete prompts don't lock the vault.
    KeyManager.get(this).onAppHidden()
    super.onStop()
  }
}
