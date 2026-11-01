# Enclavia: offline photo & video vault

React Native CLI app (no Expo) that hides photos and videos from the phone's gallery
inside an encrypted, fully offline vault.

- Browse the device gallery, multi-select photos and videos, and hide them in the vault.
- Each file is encrypted and verified. You can then delete the originals from the gallery
  (Android shows its own confirmation dialog).
- Create, rename and delete albums, and move items between them.
- Full-screen viewer with pinch-zoom and swipe. Videos play inside the app.
- **Crop photos** inside the vault: free or fixed shapes (1:1, 4:3, 3:4, 16:9, 9:16) and rotate.
  Save as a new copy or replace the original. GPS and other metadata are dropped.
- **Cut videos** inside the vault: drag start and end handles over a frame strip, with a looping
  preview. The cut is lossless (no re-encoding) and starts at the nearest keyframe. While cutting,
  the unencrypted video exists only in RAM (Android 11+), never as a file.
- Save a copy back to the gallery (`Pictures/Enclavia`, `Movies/Enclavia`). The vault copy is kept.
- Opens with your PIN only (6–16 digits): no accounts, no Google sign-in, no fingerprint. Auto-lock, and lockout after wrong PINs.

See [SECURITY.md](SECURITY.md) for the threat model and cryptographic design.

## Architecture

```
┌──────────────────────── React Native (TypeScript) ────────────────────────┐
│ screens/  Setup · Lock · Home · Album · Viewer · Player · GalleryPicker ·  │
│           Settings · ChangePin                                             │
│ state/    SessionContext (lock/unlock, revision), data hooks, item actions │
│ services/ vault.ts (typed API), permissions.ts, networkGuard.ts            │
│ native/   Codegen specs: NativeVault (TurboModule), VaultImageView,        │
│           VaultVideoView (Fabric components)                               │
└───────────────▲───────────────────────────────▲───────────────────────────┘
                │ JSI / TurboModule             │ Fabric views
┌───────────────┴───────── Kotlin (android/app/src/main/java/com/enclavia/vault)─┐
│ module/  VaultModule: async API, executors, events, gallery delete requests     │
│ crypto/  KeyManager: DEK, PIN/PBKDF2, Android Keystore, lockout, auto-lock      │
│          EncryptedFile: chunked AES-256-GCM, random-access streaming reader    │
│ data/    VaultDatabase (SQLite, encrypted text columns), VaultPaths             │
│ media/   Gallery (MediaStore paging), MediaTransfer (import/verify/export),     │
│          MediaEditor (crop, lossless cut), Imaging (thumbnails, EXIF, decode)   │
│ ui/      VaultImageView: in-memory decrypt, LRU cache, pinch-zoom               │
│ video/   VaultVideoView: Media3 ExoPlayer + decrypting DataSource               │
└─────────────────────────────────────────────────────────────────────────────────┘
```

Why the security-critical parts are native:

- **Keys never cross into JavaScript.** JS only sees item ids. Decrypted pixels go straight from
  Kotlin into the native view.
- **Performance.** Encryption streams in 64 KiB chunks using hardware AES. Thumbnails decode on a
  LIFO thread pool, so fast scrolling stays smooth. Video seeks are random-access, with no full
  decryption first.
- **No plaintext on disk.** Images decode in memory, and videos decrypt chunk by chunk while
  ExoPlayer reads them.

## Requirements

- Node ≥ 22.11, JDK 17, Android SDK 36/37 + NDK 27.1 (Android Studio installs these)
- Android device or emulator, Android 8.0 (API 26) or newer

## Run (development)

```sh
npm install
npm start            # Metro bundler
npm run android      # build + install debug app
```

> Debug builds include the INTERNET permission **only** so the app can reach the Metro bundler.
> Release builds have none.

## Build the release APK

```sh
cd android
./gradlew assembleRelease
```

Output in `android/app/build/outputs/apk/release/`:

| File | Use |
| --- | --- |
| `app-universal-release.apk` | Works on every device (largest) |
| `app-arm64-v8a-release.apk` | Modern phones (2017+); smallest |
| `app-armeabi-v7a-release.apk` | Older 32-bit phones |
| `app-x86_64-release.apk` / `app-x86-release.apk` | Emulators / Chromebooks |

For testing only, `./gradlew assembleRelease -Penclavia.qa=true` builds a variant that allows
screenshots. Never distribute that build.

Release builds are signed with `android/enclavia-release.keystore`. Its passwords are in
`android/keystore.properties`. Both files are git-ignored. **Back them up somewhere safe.**
Without them you can't publish updates that install over the existing app. A different signature
would force an uninstall, and that deletes the vault.

Verify that the APK has no network permission:

```sh
$ANDROID_HOME/build-tools/36.0.0/aapt2 dump permissions android/app/build/outputs/apk/release/app-universal-release.apk
```

## Quality checks

```sh
npm run typecheck
npm run lint
npm test
```

## Project layout

```
App.tsx, index.js                 app root, network guard install
src/native/                       codegen specs (single source of truth for the native API)
src/screens/                      UI screens
src/components/                   PinPad, MediaTile, Sheet, Header, icons…
src/state/                        session + data hooks
src/services/                     native wrapper, permissions, network guard
android/app/src/main/java/...     Kotlin vault engine (see diagram)
android/app/src/release/          manifest overlay that strips network permissions
```

## Known limitations

- **Android only.** The native vault engine is Kotlin. An iOS build needs a Swift port of
  `crypto/`, `data/`, `media/` and the two views. The JS layer is platform-neutral.
- Animated GIFs show their first frame.
- Deleting originals removes them from the phone. Copies already uploaded to cloud services
  (Google Photos, OneDrive…) must be deleted there separately.
- Uninstalling the app or clearing its storage permanently destroys the vault, by design.
