# Ball Jumper

Ball Jumper is a native Android 3D vertical-jumping game written in Kotlin. It uses an OpenGL ES game scene, a custom fixed-step physics loop, Firebase Realtime Database multiplayer, achievements, skins, daily missions, Endless Run, and Time Trial.

## Requirements

- JDK 17 or 21
- Android SDK Platform 35 and Build Tools 35.x
- Android 7.0 (API 24) or newer
- Firebase Android apps for `com.example.skyboundjumper` and `com.example.skyboundjumper.debug` when online multiplayer is required

## Restore Firebase multiplayer after cloning

The repository builds and opens without Firebase, but online multiplayer stays disabled until local Firebase configuration is restored. Real Firebase configuration is intentionally excluded from Git.

1. In Firebase Console, register the release package `com.example.skyboundjumper` and the debug package `com.example.skyboundjumper.debug`.
2. Enable Anonymous authentication and create a Realtime Database.
3. Download the combined `google-services.json` and save it as `app/google-services.json`.
4. Copy `firebase.properties.example` to `firebase.properties` and replace its database URL.
5. Optionally copy `.firebaserc.example` to `.firebaserc`, select the project, and deploy the committed rules with `firebase deploy --only database`.

The templates document every required field without containing a live key. The committed `firebase-database.rules.json` restricts matchmaking and match data to authenticated players. Restrict the Android API key in Google Cloud Console to the two package names and the SHA-1/SHA-256 fingerprints of the certificates that sign them.

## Build a debug APK

```powershell
.\gradlew.bat clean assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## Configure release signing

Create the upload keystore outside source control:

```powershell
keytool -genkeypair -v -keystore release-key.jks -alias skybound -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
```

Copy `keystore.properties.example` to the ignored `keystore.properties` file and fill in the real values, or provide these environment variables:

```powershell
$env:ANDROID_KEYSTORE_PATH = "release-key.jks"
$env:ANDROID_KEYSTORE_PASSWORD = "your-store-password"
$env:ANDROID_KEY_ALIAS = "skybound"
$env:ANDROID_KEY_PASSWORD = "your-key-password"
```

Never commit a keystore, signing passwords, `google-services.json`, `firebase.properties`, `.firebaserc`, `local.properties`, an APK, or an AAB.

## Build release files

```powershell
.\gradlew.bat clean assembleRelease
.\gradlew.bat bundleRelease
```

Outputs:

- APK: `app/build/outputs/apk/release/app-release.apk`
- Play bundle: `app/build/outputs/bundle/release/app-release.aab`

Increment `versionCode` before every Google Play upload and keep the upload key backed up securely.

## What belongs in GitHub

Commit Kotlin source, manifests, Gradle configuration and wrapper files, ProGuard rules, sanitized Firebase templates, Firebase database rules, tests, documentation, and intentional game assets under `app/src/main/assets`.

Do not commit local SDK paths, IDE metadata, Gradle caches, build folders, logs, screenshots used only for QA, compiled test classes, recovery folders, release exports, or signing material. The repository `.gitignore` enforces these exclusions.
