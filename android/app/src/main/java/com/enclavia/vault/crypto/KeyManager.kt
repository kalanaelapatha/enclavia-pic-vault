package com.enclavia.vault.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.biometric.BiometricManager
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
 *    ├─ PIN wrap:        Keystore_AES( AES-GCM( PBKDF2(PIN, salt), DEK ) )
 *    │                   -> PIN guessing must happen on this device (hardware-bound key)
 *    └─ Biometric wrap:  Keystore_AES[user-auth-required, invalidated on new enrollment]( DEK )
 *
 * Wrong PIN attempts are rate limited with an exponential lockout.
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
    private const val BIO_ALIAS = "enclavia_bio_kek_v1"
    private const val PREFS = "enclavia_keys"
    private const val K_DEK_PIN = "dek_pin"
    private const val K_DEK_BIO = "dek_bio"
    private const val K_SALT = "pin_salt"
    private const val K_ITER = "pin_iter"
    private const val K_FAILS = "fail_count"
    private const val K_LOCKOUT = "lockout_until"
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

  // ---------------------------------------------------------------- state

  fun isSetup(): Boolean = prefs.contains(K_DEK_PIN)

  fun isUnlocked(): Boolean = dek != null

  fun failedAttempts(): Int = prefs.getInt(K_FAILS, 0)

  fun lockoutUntil(): Long = prefs.getLong(K_LOCKOUT, 0L)

  fun isBiometricEnabled(): Boolean = prefs.contains(K_DEK_BIO)

  fun isBiometricAvailable(): Boolean =
    BiometricManager.from(appContext).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
      BiometricManager.BIOMETRIC_SUCCESS

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
    prefs.edit().putInt(K_FAILS, 0).putLong(K_LOCKOUT, 0L).commit()
    dek = newDek
  }

  @Synchronized
  fun unlockWithPin(pin: String): UnlockResult {
    val now = System.currentTimeMillis()
    val lockout = lockoutUntil()
    if (now < lockout) return UnlockResult(false, failedAttempts(), lockout)

    val opened = tryOpenPinWrap(pin)
    if (opened != null) {
      dek?.fill(0)
      dek = opened
      prefs.edit().putInt(K_FAILS, 0).putLong(K_LOCKOUT, 0L).commit()
      return UnlockResult(true, 0, 0L)
    }
    val fails = failedAttempts() + 1
    var until = 0L
    if (fails >= FREE_ATTEMPTS) {
      val shift = (fails - FREE_ATTEMPTS).coerceAtMost(10)
      until = now + (BASE_LOCKOUT_MS shl shift).coerceAtMost(MAX_LOCKOUT_MS)
    }
    prefs.edit().putInt(K_FAILS, fails).putLong(K_LOCKOUT, until).commit()
    return UnlockResult(false, fails, until)
  }

  @Synchronized
  fun changePin(oldPin: String, newPin: String): Boolean {
    validatePin(newPin)
    val current = tryOpenPinWrap(oldPin) ?: return false
    try {
      writePinWrap(newPin, current)
    } finally {
      current.fill(0)
    }
    return true
  }

  private fun validatePin(pin: String) {
    require(pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH && pin.all { it.isDigit() }) {
      "PIN must be $MIN_PIN_LENGTH-$MAX_PIN_LENGTH digits"
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

  // ---------------------------------------------------------------- biometric

  /** Cipher to authorise with BiometricPrompt when enabling biometric unlock. */
  fun createBiometricEnrollCipher(): Cipher {
    if (!isUnlocked()) throw VaultLockedException()
    deleteKey(BIO_ALIAS)
    val key = generateKey(BIO_ALIAS, requireBiometric = true)
    return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
  }

  fun completeBiometricEnroll(authorisedCipher: Cipher) {
    withDek { key ->
      val ct = authorisedCipher.doFinal(key)
      prefs.edit().putString(K_DEK_BIO, b64(authorisedCipher.iv + ct)).commit()
    }
  }

  /** Cipher to authorise with BiometricPrompt for unlocking, or null if biometric is unusable. */
  fun createBiometricUnlockCipher(): Cipher? {
    val blob = prefs.getString(K_DEK_BIO, null)?.let { unb64(it) } ?: return null
    return try {
      val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
      val key = ks.getKey(BIO_ALIAS, null) as? SecretKey ?: run { disableBiometric(); return null }
      Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
      }
    } catch (e: KeyPermanentlyInvalidatedException) {
      // A new fingerprint/face was enrolled: the biometric key is gone by design.
      disableBiometric()
      null
    }
  }

  @Synchronized
  fun completeBiometricUnlock(authorisedCipher: Cipher) {
    val blob = unb64(prefs.getString(K_DEK_BIO, null) ?: throw IllegalStateException("Biometric disabled"))
    val opened = authorisedCipher.doFinal(blob, 12, blob.size - 12)
    dek?.fill(0)
    dek = opened
    prefs.edit().putInt(K_FAILS, 0).putLong(K_LOCKOUT, 0L).commit()
  }

  fun disableBiometric() {
    prefs.edit().remove(K_DEK_BIO).commit()
    deleteKey(BIO_ALIAS)
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
    if (timeout <= 0L) lock() else mainHandler.postDelayed(lockRunnable, timeout)
  }

  fun onAppVisible() {
    mainHandler.removeCallbacks(lockRunnable)
  }

  // ---------------------------------------------------------------- wipe

  @Synchronized
  fun wipeKeys() {
    lock()
    prefs.edit().clear().commit()
    deleteKey(DEVICE_ALIAS)
    deleteKey(BIO_ALIAS)
  }

  // ---------------------------------------------------------------- primitives

  private fun deviceKey(): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (ks.getKey(DEVICE_ALIAS, null) as? SecretKey)?.let { return it }
    return generateKey(DEVICE_ALIAS, requireBiometric = false)
  }

  private fun generateKey(alias: String, requireBiometric: Boolean): SecretKey {
    fun spec(strongBox: Boolean): KeyGenParameterSpec =
      KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .setRandomizedEncryptionRequired(true)
        .apply {
          if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true)
          if (requireBiometric) {
            setUserAuthenticationRequired(true)
            setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
              setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            }
          }
        }
        .build()

    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    val hasStrongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
      appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
    if (hasStrongBox && !requireBiometric) {
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
