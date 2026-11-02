package com.enclavia.vault.crypto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class VaultLockedException : IllegalStateException("Vault is locked")

/**
 * Owns the vault's Data Encryption Key (DEK).
 *
 * Key hierarchy:
 *   DEK (random 256-bit, only ever in RAM while unlocked)
 *    └─ PIN wrap:        Keystore_AES( AES-GCM( PBKDF2(PIN, salt), DEK ) )
 *    │                   -> PIN guessing must happen on this device (hardware-bound key)
 *
 * Wrong PIN attempts are rate limited with an exponential lockout. The lockout is timed with the
 * time since boot, so changing the phone's clock can't shorten it.
 */
class KeyManager private constructor(context: Context) {

  data class UnlockResult(val ok: Boolean, val failedAttempts: Int, val lockoutUntil: Long)

  companion object {
    const val MIN_PIN_LENGTH = 6
    const val MAX_PIN_LENGTH = 16
    private const val FREE_ATTEMPTS = 5
    private const val BASE_LOCKOUT_MS = 30_000L
    private const val MAX_LOCKOUT_MS = 60L * 60_000L

    private const val DEVICE_ALIAS = "enclavia_device_kek_v1"
    private const val PREFS = "enclavia_keys"
    private const val K_DEK_PIN = "dek_pin"
    private const val K_SALT = "pin_salt"
    private const val K_ITER = "pin_iter"
    private const val K_FAILS = "fail_count"
    private const val K_LOCK_MS = "lockout_ms"
    private const val K_LOCK_END = "lockout_end_elapsed"
    private const val K_LOCK_BOOT = "lockout_boot"
    private const val K_AUTOLOCK = "autolock_ms"
    private val PIN_AAD = "enclavia-dek-pin-v1".toByteArray()

    @Volatile private var instance: KeyManager? = null

    fun get(context: Context): KeyManager =
      instance ?: synchronized(this) {
        instance ?: KeyManager(context.applicationContext).also { instance = it }
      }
  }

  private val appContext = context.applicationContext
  private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
  private val rng = SecureRandom()
  private val lockListeners = CopyOnWriteArraySet<() -> Unit>()
  private val mainHandler = Handler(Looper.getMainLooper())
  private val lockRunnable = Runnable { lock() }

  @Volatile private var dek: ByteArray? = null

  /** elapsedRealtime when the app went to the background; 0 while visible. */
  @Volatile private var hiddenAt = 0L

  init {
    // Turning the screen off always locks, whatever the auto-lock delay is.
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_SCREEN_OFF) lock()
      }
    }
    val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
      appContext.registerReceiver(receiver, filter)
    }
  }

  // ---------------------------------------------------------------- state

  fun isSetup(): Boolean = prefs.contains(K_DEK_PIN)

  fun isUnlocked(): Boolean = dek != null

  fun failedAttempts(): Int = prefs.getInt(K_FAILS, 0)

  /** Wall-clock end of the current lockout, for the countdown on screen (0 = none). */
  fun lockoutUntil(): Long {
    val left = lockoutRemaining()
    return if (left > 0) System.currentTimeMillis() + left else 0L
  }

  private fun bootCount(): Int =
    runCatching { Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

  /** Lockout time left, measured on the boot clock (not changeable from Settings). */
  private fun lockoutRemaining(): Long {
    val duration = prefs.getLong(K_LOCK_MS, 0L)
    if (duration <= 0L) return 0L
    val now = SystemClock.elapsedRealtime()
    val end = prefs.getLong(K_LOCK_END, 0L)
    val boot = bootCount()
    val sameBoot = if (boot != -1) boot == prefs.getInt(K_LOCK_BOOT, -2) else now >= end - duration
    // After a restart the boot clock starts again from zero: wait the full lockout since boot.
    val deadline = if (sameBoot) end else duration
    return (deadline - now).coerceIn(0L, duration)
  }

  /**
   * Counts a failed attempt *before* the PIN is checked, so stopping the app mid-check
   * can't give a free guess. [clearFailures] undoes it when the PIN is right.
   */
  private fun recordFailedAttempt(): Int {
    val fails = failedAttempts() + 1
    val duration = if (fails >= FREE_ATTEMPTS) {
      (BASE_LOCKOUT_MS shl (fails - FREE_ATTEMPTS).coerceAtMost(10)).coerceAtMost(MAX_LOCKOUT_MS)
    } else 0L
    prefs.edit()
      .putInt(K_FAILS, fails)
      .putLong(K_LOCK_MS, duration)
      .putLong(K_LOCK_END, SystemClock.elapsedRealtime() + duration)
      .putInt(K_LOCK_BOOT, bootCount())
      .commit()
    return fails
  }

  private fun clearFailures() {
    prefs.edit().putInt(K_FAILS, 0).putLong(K_LOCK_MS, 0L).commit()
  }

  /**
   * Runs [block] with a private copy of the DEK, so a concurrent lock() can never
   * hand an operation a half-wiped key. The copy is wiped afterwards.
   */
  fun <T> withDek(block: (ByteArray) -> T): T {
    val copy = dek?.copyOf() ?: throw VaultLockedException()
    try {
      return block(copy)
    } finally {
      copy.fill(0)
    }
  }

  fun addLockListener(listener: () -> Unit) = lockListeners.add(listener)

  fun removeLockListener(listener: () -> Unit) = lockListeners.remove(listener)

  // ---------------------------------------------------------------- PIN

  @Synchronized
  fun setupPin(pin: String) {
    check(!isSetup()) { "Vault already set up" }
    validatePin(pin)
    val newDek = ByteArray(32).also { rng.nextBytes(it) }
    writePinWrap(pin, newDek)
    clearFailures()
    dek = newDek
  }

  @Synchronized
  fun unlockWithPin(pin: String): UnlockResult {
    if (lockoutRemaining() > 0) return UnlockResult(false, failedAttempts(), lockoutUntil())
    val fails = recordFailedAttempt()
    val opened = tryOpenPinWrap(pin)
    if (opened != null) {
      dek?.fill(0)
      dek = opened
      clearFailures()
      return UnlockResult(true, 0, 0L)
    }
    return UnlockResult(false, fails, lockoutUntil())
  }

  /** Returns false for a wrong current PIN. Wrong guesses count towards the same lockout as unlocking. */
  @Synchronized
  fun changePin(oldPin: String, newPin: String): Boolean {
    validatePin(newPin)
    check(lockoutRemaining() == 0L) { "Too many wrong PINs. Try again later." }
    recordFailedAttempt()
    val current = tryOpenPinWrap(oldPin) ?: return false
    clearFailures()
    try {
      writePinWrap(newPin, current)
    } finally {
      current.fill(0)
    }
    return true
  }

  /** Same rules as the app's PIN screen, enforced here too. */
  private fun validatePin(pin: String) {
    require(pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it.isDigit() }) {
      "PIN must be $MIN_PIN_LENGTH-$MAX_PIN_LENGTH digits"
    }
    require(pin.toSet().size > 1) { "Avoid repeating the same digit" }
    require(!"01234567890123456789".contains(pin) && !"98765432109876543210".contains(pin)) {
      "Avoid simple sequences"
    }
  }

  private fun writePinWrap(pin: String, key: ByteArray) {
    val salt = ByteArray(16).also { rng.nextBytes(it) }
    val iterations = calibrateIterations()
    val kek = pbkdf2(pin, salt, iterations)
    try {
      val inner = rawSeal(kek, key, PIN_AAD)
      val outer = keystoreSeal(deviceKey(), inner)
      prefs.edit()
        .putString(K_SALT, b64(salt))
        .putInt(K_ITER, iterations)
        .putString(K_DEK_PIN, b64(outer))
        .commit()
    } finally {
      kek.fill(0)
    }
  }

  private fun tryOpenPinWrap(pin: String): ByteArray? {
    val outer = prefs.getString(K_DEK_PIN, null)?.let { unb64(it) } ?: return null
    val salt = unb64(prefs.getString(K_SALT, null) ?: return null)
    val iterations = prefs.getInt(K_ITER, 0)
    val inner = keystoreOpen(deviceKey(), outer)
    val kek = pbkdf2(pin, salt, iterations)
    return try {
      rawOpen(kek, inner, PIN_AAD)
    } catch (e: AEADBadTagException) {
      null
    } finally {
      kek.fill(0)
    }
  }

  /** Picks a PBKDF2 cost of roughly 350ms on this device (min 120k, max 1M iterations). */
  private fun calibrateIterations(): Int {
    val probe = 20_000
    val start = SystemClock.elapsedRealtime()
    pbkdf2("000000", ByteArray(16), probe).fill(0)
    val elapsed = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1)
    return (probe * 350L / elapsed).coerceIn(120_000L, 1_000_000L).toInt()
  }

  private fun pbkdf2(pin: String, salt: ByteArray, iterations: Int): ByteArray {
    val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, 256)
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
      spec.clearPassword()
    }
  }

  // ---------------------------------------------------------------- lock / auto-lock

  fun lock() {
    mainHandler.removeCallbacks(lockRunnable)
    val old = dek ?: return
    dek = null
    old.fill(0)
    lockListeners.forEach { runCatching { it() } }
  }

  fun autoLockMs(): Long = prefs.getLong(K_AUTOLOCK, 0L)

  fun setAutoLockMs(ms: Long) {
    prefs.edit().putLong(K_AUTOLOCK, ms.coerceIn(0L, 10L * 60_000L)).apply()
  }

  /** Activity became invisible (home button, app switcher, screen off). */
  fun onAppHidden() {
    if (!isUnlocked()) return
    val timeout = autoLockMs()
    hiddenAt = SystemClock.elapsedRealtime()
    if (timeout <= 0L) lock() else mainHandler.postDelayed(lockRunnable, timeout)
  }

  fun onAppVisible() {
    mainHandler.removeCallbacks(lockRunnable)
    // The delayed lock doesn't run while the phone sleeps; check the real time that has passed.
    val since = hiddenAt
    hiddenAt = 0L
    if (since > 0L && SystemClock.elapsedRealtime() - since >= autoLockMs()) lock()
  }

  // ---------------------------------------------------------------- wipe

  @Synchronized
  fun wipeKeys() {
    lock()
    prefs.edit().clear().commit()
    deleteKey(DEVICE_ALIAS)
  }

  // ---------------------------------------------------------------- primitives

  private fun deviceKey(): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (ks.getKey(DEVICE_ALIAS, null) as? SecretKey)?.let { return it }
    return generateKey(DEVICE_ALIAS)
  }

  private fun generateKey(alias: String): SecretKey {
    fun spec(strongBox: Boolean): KeyGenParameterSpec =
      KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setRandomizedEncryptionRequired(true)
        .apply {
          if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true)
        }
        .build()

    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    val hasStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
      appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    if (hasStrongBox) {
      try {
        generator.init(spec(strongBox = true))
        val key = generator.generateKey()
        selfTest(key)
        return key
      } catch (e: Exception) {
        deleteKey(alias) // fall back to the TEE
      }
    }
    generator.init(spec(strongBox = false))
    return generator.generateKey()
  }

  private fun selfTest(key: SecretKey) {
    val probe = ByteArray(32).also { rng.nextBytes(it) }
    check(keystoreOpen(key, keystoreSeal(key, probe)).contentEquals(probe)) { "Keystore self-test failed" }
  }

  private fun deleteKey(alias: String) {
    runCatching {
      KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }
  }

  private fun keystoreSeal(key: SecretKey, data: ByteArray): ByteArray {
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, key)
    return c.iv + c.doFinal(data)
  }

  private fun keystoreOpen(key: SecretKey, blob: ByteArray): ByteArray {
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
    return c.doFinal(blob, 12, blob.size - 12)
  }

  private fun rawSeal(key: ByteArray, data: ByteArray, aad: ByteArray): ByteArray {
    val iv = ByteArray(12).also { rng.nextBytes(it) }
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    c.updateAAD(aad)
    return iv + c.doFinal(data)
  }

  private fun rawOpen(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray {
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, 12))
    c.updateAAD(aad)
    return c.doFinal(blob, 12, blob.size - 12)
  }

  private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

  private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}

/** Encrypts short text values (album names, file names) stored in the database. */
object FieldCipher {
  private val rng = SecureRandom()
  private val AAD = "enclavia-field-v1".toByteArray()
  private val LABEL = "field".toByteArray() // domain-separated sub-key; never collides with 16-byte file salts

  private fun key(dek: ByteArray) = EncryptedFile.deriveFileKey(dek, LABEL)

  fun encrypt(plain: String, dek: ByteArray): String {
    val iv = ByteArray(12).also { rng.nextBytes(it) }
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, key(dek), GCMParameterSpec(128, iv))
    c.updateAAD(AAD)
    return Base64.encodeToString(iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
  }

  fun decrypt(enc: String, dek: ByteArray): String {
    val blob = Base64.decode(enc, Base64.NO_WRAP)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, key(dek), GCMParameterSpec(128, blob, 0, 12))
    c.updateAAD(AAD)
    return String(c.doFinal(blob, 12, blob.size - 12), Charsets.UTF_8)
  }
}
