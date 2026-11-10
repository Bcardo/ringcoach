# RingCoach

Native Android prototype for the Galaxy Z Fold7. The first milestone is a debug build that installs and shows `原型已启动` (prototype started).

## Build on Windows

1. Install JDK 17 or 21 and set `JAVA_HOME` to its installation directory.
2. In Android Studio's SDK Manager, install Android SDK Platform 36, Android SDK Build-Tools 35.0.0, and Android SDK Platform-Tools.
3. Set `ANDROID_HOME` to the SDK directory. Android Studio can also configure the SDK for the project in `local.properties`.
4. From the project root, run `./gradlew.bat :app:assembleDebug` in PowerShell. The APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Install and verify on the Fold7

If Developer options is hidden, go to **Settings → About phone → Software information** and tap **Build number** seven times. Return to Settings, open **Developer options**, and enable **USB debugging**. Connect the unlocked Fold7 over USB and accept the debugging prompt. [Samsung Developer options guide](https://developer.samsung.com/health/data/guide/phone-developer-options.html)

Run the following in PowerShell with `ANDROID_HOME` set to the SDK directory.

```powershell
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
& $adb devices
& $adb install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb shell am start -W -n com.bcardo.ringcoach/.MainActivity
```

Check that `adb devices` lists the Fold7 as `device`, the install reports `Success`, and RingCoach displays `原型已启动` on the phone. If multiple devices are connected, pass `-s <serial>` to `adb` before the `install` and `shell` commands.

For a manual install, copy `app-debug.apk` to the phone, open it from **My Files**, and follow the installation prompt. Then open RingCoach and check for `原型已启动`. [Samsung app installation help](https://www.samsung.com/us/support/troubleshoot/TSG10001914/)
