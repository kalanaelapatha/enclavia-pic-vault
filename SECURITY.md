# Enclavia security model

## Goals

1. Hidden media must not be visible to the gallery, other apps, backups or anyone holding the
   unlocked phone without the PIN.
2. Data must stay unreadable if the app's private storage is copied off the device (root, forensic
   image, backup extraction).
3. The app must never send anything over a network.

## Isolation from the internet

| Layer | Control |
| --- | --- |
| OS | The release manifest declares **no `INTERNET` permission**. `src/release/AndroidManifest.xml` uses `tools:node="remove"` to strip `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` and others, even if a library adds them. Without `INTERNET`, Android refuses to create sockets for the app (EACCES). |
| JS | `installNetworkGuard()` replaces `fetch`, `XMLHttpRequest` and `WebSocket` with stubs that throw (release builds). |
| Dependencies | No analytics, crash reporting, ads or remote config. Media3 only uses our local `EncryptedDataSource`. |
| Transport config | `usesCleartextTraffic=false` in release. |

Debug builds add `INTERNET` from `src/debug/AndroidManifest.xml`, only for the Metro dev server.

## Storage isolation

- Vault files live in `noBackupFilesDir/vault/`: app-private, not scanned by MediaStore, never
  backed up.
- `allowBackup=false`, `fullBackupContent=false`, and `dataExtractionRules` exclude every domain
  for cloud backup and device transfer.
- File names on disk are random UUIDs, with no extensions.

## Cryptography

```
DEK  = 32 random bytes (SecureRandom), held only in RAM while unlocked

PIN wrap   = KeystoreAES-GCM[ device KEK, StrongBox if available ](
               AES-256-GCM[ PBKDF2-HMAC-SHA256(PIN, salt16, ~350ms, ≥120k iters) ](DEK) )

fileKey    = HMAC-SHA256(DEK, "enclavia-file-v1" ‖ salt16)        (one per file)
fieldKey   = HMAC-SHA256(DEK, "enclavia-file-v1" ‖ "field")       (DB text columns)
```

**File format** (`EncryptedFile.kt`): a 25-byte header (`ENCV`, version, chunk size, salt) followed by
64 KiB chunks, each sealed with AES-256-GCM.

- The nonce is the chunk index, so reordering chunks is detected.
- The additional authenticated data is the header plus a final-chunk flag, so truncation and
  header tampering are detected.
- This gives authenticated random access, which video seeking needs.

**Import integrity.** While the original is encrypted, its SHA-256 is computed. The encrypted file
is then fully decrypted and hashed again. The file is committed only if the two hashes match.
Deleting the original is offered only for items that passed this check.

**Metadata.** Original file names and album names are encrypted (AES-GCM, random IV per value).
Type, size, dimensions and dates are stored in plaintext so lists can be sorted. They reveal no
content.

## Authentication

- **PIN:** 6–16 digits. Weak PINs (repeated digits, simple sequences) are rejected. The PIN is
  entered on a custom keypad, never the system keyboard, so keyboard apps can't record it.
- **Lockout:** 5 free attempts, then 30 s doubling up to 1 h (`KeyManager.unlockWithPin`).
  - The lockout runs on the boot clock (`elapsedRealtime` + boot count), so changing the phone's
    date or time doesn't shorten it. After a restart, the full lockout applies again from boot.
  - Each attempt is counted *before* the PIN is checked, so force-stopping the app mid-check
    doesn't give a free guess.
  - Wrong current PINs on the Change PIN screen count towards the same lockout.
  - The weak-PIN rules (repeated digits, simple sequences) are enforced natively as well as in the UI.
- **Offline brute force:** the PIN-wrapped key is also wrapped by a non-exportable Keystore key.
  Guessing therefore has to run on the device itself, at PBKDF2 cost per guess.
- **PIN only:** no accounts, no Google/Gmail sign-in, no fingerprint or face unlock. The PIN is the
  only way to derive the key. Release builds strip the biometric permissions.

## Runtime protections

- `FLAG_SECURE` blocks screenshots (only a QA build made with `-Penclavia.qa=true` turns it off), screen recording, casting and the recent-apps thumbnail.
- Alert dialogs (which can show album names) also get `FLAG_SECURE`. On Android 13+, the
  recent-apps screenshot is turned off as well.
- `filterTouchesWhenObscured` drops touches while another app's window covers ours (tapjacking).
  On Android 12+, other apps' floating windows are hidden while Enclavia is on screen
  (`setHideOverlayWindows`).
- Other apps can't read the screen contents:
  - autofill is off for the whole window;
  - on-device content capture is off (Android 10+);
  - on Android 14+, the window is marked accessibility-data-sensitive, so only real accessibility
    tools such as TalkBack can read or tap it.
- The app exposes nothing to other apps: the launcher screen is the only exported component, and
  it has no deep links. There are no services, content providers or broadcast receivers that other
  apps can reach. The only exception is the AndroidX profile installer, which requires the
  system-only `DUMP` permission.
- **Screen off always locks immediately**, whatever the auto-lock setting.
- **Auto-lock** runs on `onStop`: immediately by default, or after 30 s, 1 min or 5 min. The delay
  is measured in real time, including deep sleep. Locking
  wipes the DEK, clears every decoded-bitmap cache, stops video playback, and unmounts every vault
  screen (auth-gated navigator).
- System dialogs (permission, delete confirmation) only trigger `onPause`, so they don't
  lock the vault.
- Every TurboModule operation takes a private copy of the DEK and wipes it afterwards. A concurrent
  lock can never corrupt an in-progress write.
- **Editing** (photo crop, video cut, video crop): output is re-encrypted and hash-verified before
  it replaces anything. Unencrypted video output is written to an anonymous RAM file (`memfd`,
  Android 11+, under 400 MB) or otherwise to a private temp file that is deleted right away.
  Video crop decodes straight from the encrypted file, keeps only the orientation tag (location
  and other metadata are dropped), and is cancelled if the vault locks.
- Input validation:
  - Item and album ids must be UUIDs, so they can't be used for path traversal.
  - Gallery URIs must be `content://media/...`.
  - Names are sanitised and length-capped.
- **Release hardening:** R8 minification and resource shrinking, `debuggable=false`, verbose
  `Log` calls stripped, and a dedicated 4096-bit RSA signing key.
  - `debuggable=false` means `run-as`, debugger attach and heap dumps aren't possible without root.
  - QA builds (screenshots allowed) use a separate app id, `com.enclavia.vault.qa`, so they can
    never replace or read the real vault.
  - Debug builds are signed with a different key, so they can't replace a release install either.

## Residual risks (by design or out of scope)

- A rooted or compromised device can read the app's memory while the vault is unlocked.
- A 6-digit PIN has 10⁶ possibilities. Someone running code *on the device* who can reset
  the lockout counter can try them all at PBKDF2 speed (about 4 days at 350 ms per guess). Longer
  PINs scale this up quickly.
- The JVM can't guarantee that every copy of a key in memory is wiped, so wiping is best effort.
- Flash storage can't guarantee secure erase of the original gallery file. Deletion goes through
  MediaStore. Cloud copies are outside the app's control.
- Exported copies are plaintext in the shared gallery. The app warns before every export.
- On Android 13 and older, a malicious accessibility service that the user has enabled can read
  the screen, including which PIN keys are pressed. Only grant accessibility access to apps you
  trust.
- The phone's own gallery or cloud app (Google Photos, Mi Cloud, the MIUI Gallery "Recently
  deleted" bin) may still hold a copy of an imported file.
