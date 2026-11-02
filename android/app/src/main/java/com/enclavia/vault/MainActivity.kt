package com.enclavia.vault

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.contentcapture.ContentCaptureManager
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.enclavia.vault.crypto.KeyManager
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

class MainActivity : ReactActivity() {

  private val secure = !BuildConfig.ALLOW_SCREENSHOTS

  override fun getMainComponentName(): String = "EnclaviaVault"

  override fun createReactActivityDelegate(): ReactActivityDelegate =
    DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

  override fun onCreate(savedInstanceState: Bundle?) {
    // Block screenshots, screen recording, casting and the recent-apps preview.
    if (secure) {
      window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
    }
    // Android 12+: other apps' floating windows (chat heads, fake overlays) are hidden while we're open.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)
    // react-native-screens requires a null state (fragments are restored by React, not Android).
    super.onCreate(null)

    val root = window.decorView
    // Ignore touches while another app draws on top of us (tapjacking on the PIN pad).
    root.filterTouchesWhenObscured = true
    // Keep autofill services and on-device "screen understanding" features from reading the screen.
    root.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      getSystemService(ContentCaptureManager::class.java)?.setContentCaptureEnabled(false)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      root.importantForContentCapture = View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS
    }
    // Android 14+: only real accessibility tools (e.g. TalkBack) may read or tap the app.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      root.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
    }
    // Alerts open in their own window, which doesn't inherit FLAG_SECURE; they can show album names.
    if (secure) supportFragmentManager.registerFragmentLifecycleCallbacks(SecureDialogs, true)
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

  private object SecureDialogs : FragmentManager.FragmentLifecycleCallbacks() {
    override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
      (f as? DialogFragment)?.dialog?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
  }
}
