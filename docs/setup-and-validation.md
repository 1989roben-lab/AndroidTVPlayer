# Setup And Validation

## What is required to build

You need all three of the following on the development machine:

- JDK 17
- Android Studio with Android SDK
- `adb` from Android Platform Tools

This repository now includes Gradle Wrapper, so you do not need a separately installed Gradle binary.

## Recommended local setup

### 1. Install JDK 17

On macOS, install a JDK that Android Gradle Plugin 8.7 can use. JDK 17 is the safe target for this project.

### 2. Install Android Studio

Install the current stable Android Studio and make sure these SDK components are present:

- Android SDK Platform 35
- Android SDK Build-Tools
- Android SDK Platform-Tools
- Android Emulator
- Android TV system image

### 3. Confirm environment

After installation, these commands should work:

```bash
java -version
adb version
./gradlew -v
```

## Build commands

### Debug APK

```bash
./gradlew :app:assembleDebug
```

Expected output directory:

- `app/build/outputs/apk/debug/app-debug.apk`

### Release APK

Later, after signing config is added:

```bash
./gradlew :app:assembleRelease
```

## Validation strategy

### What can be verified in Android TV Emulator

- App launch
- TV launcher visibility
- D-pad focus and navigation
- Home screen and player screen flows
- Media3 playback using ordinary network video URLs
- Basic crash and lifecycle issues

### What should be verified on Xiaomi TV

- APK install behavior
- Xiaomi TV launcher presentation
- Real decoder compatibility
- Remote-control feel
- AirPlay / DLNA discovery on the real LAN
- End-to-end protocol stability

## Best workflow

1. Develop and smoke test in Android TV Emulator.
2. Install debug APK to Xiaomi TV for device-specific checks.
3. Do all final protocol validation on Xiaomi TV.

## Installing to Xiaomi TV

If developer options and ADB debugging are enabled on the TV:

```bash
adb connect TV_IP:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If wireless debugging is not available, use USB or transfer the APK by U disk.

## Creating an Android TV Emulator

Inside Android Studio:

1. Open Device Manager.
2. Create a new virtual device.
3. Choose a TV hardware profile.
4. Choose a Google TV or Android TV system image.
5. Start the emulator and run the app.

## Current limitation on this machine

At the time this document was written, the current machine did not have:

- Java runtime
- Android SDK
- `adb`

That means the repository is prepared for build and install, but actual APK generation still depends on completing local environment setup.
