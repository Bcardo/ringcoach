# Direct Ring Bluetooth research

RingCoach now has an opt-in [production-app collection integration](../../docs/ring-bluetooth-collection.md). This standalone package remains a research tool; avoid running both collectors during routine use. Preserve any active capture before switching collection to RingCoach.

This standalone Android application is separate from RingCoach. It preserves the Ring's existing bond and does not modify RingCoach's permissions or accessibility service. It is a research tool, not an implemented production HRV reader.

Verified through November 14: MTU 498, ten services, Data notification subscription, battery message-2 response, direct health transfer and passive overnight reception of timestamped HRV plus several other health streams. Older HRV windows match the existing reference; fresh-night reference validation and multi-night reliability remain pending. See [the findings](../../docs/galaxy-ring-bluetooth.md).

## Build and short probe

Use the root project's JDK/SDK/Gradle setup. From the repository root:

```powershell
.\gradlew.bat -p research/bluetooth-probe assembleDebug lintDebug
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
& $adb install -r research/bluetooth-probe/build/outputs/apk/debug/RingBluetoothProbe-debug.apk
& $adb shell pm grant com.bcardo.ringcoach.bleresearch android.permission.BLUETOOTH_CONNECT
& $adb shell pm grant com.bcardo.ringcoach.bleresearch android.permission.POST_NOTIFICATIONS
& $adb shell am start -W -n com.bcardo.ringcoach.bleresearch/.ProbeActivity
& $adb shell am instrument --no-restart -w -r -e class com.bcardo.ringcoach.bleresearch.DirectRingBluetoothResearchTest -e direct_ring_research true -e request_health true -e observe_seconds 30 com.bcardo.ringcoach.bleresearch/androidx.test.runner.AndroidJUnitRunner
```

Grant the research permissions only with the owner's agreement. The instrumented test runs under its own application UID. The optional `request_health` flag issues a bounded two-day read request. The test does not acknowledge history. Its test-runner success means the transport probe completed; inspect its report to determine whether any useful records arrived. Optional hello response is separately opt-in and remains unverified.

Raw probe frames and reports are in this package's private `files/direct-ring-research/` directory. Do not publish them. Never unpair, factory reset, or issue debug commands to make a probe pass.

## Overnight passive capture

Obtain the owner's approval to leave the recorder running. Do not reinstall or force-stop the research package during a capture. Use the app's start/stop buttons, or the explicit control intents below. Recreating only the research activity avoids stale intent delivery after a package update.

```powershell
& $adb shell am start -W -f 0x10008000 -n com.bcardo.ringcoach.bleresearch/.ProbeActivity --ez start_capture true
# Stop through the app; its service is private and rejects direct external control.
& $adb shell am start -W -f 0x10008000 -n com.bcardo.ringcoach.bleresearch/.ProbeActivity --ez stop_capture true
```

The notification says recording is active. The service subscribes to the Data service without sending any protocol command. It records only Ring notifications in app-private JSONL, retains existing files, reconnects after a link drop, and ends after 18 hours or 32 MiB. It has no Internet permission. Wear the Ring normally with the phone nearby and Samsung's normal companion available. No Samsung Health screen navigation is required by this recorder. A killed process does not silently restart; review the live service and capture status before claiming continuity.

Fetch without interrupting the capture:

```powershell
python scripts/fetch_ring_capture.py --adb $adb --out private/bluetooth-overnight
```

The summary prints packet/feature counts, not readings or identifiers. After recording, analyze the captured JSONL. Optional comparison expects the local reference format `[start_ms,end_ms,rmssd,sdnn]`, as produced by the existing private diagnostic parser:

```powershell
python scripts/analyze_ring_capture.py private/bluetooth-overnight/capture-TIMESTAMP.jsonl --reference PRIVATE-REFERENCE.records.json --private-hrv-output private/bluetooth-overnight/decoded-hrv.json
```

Confirm capture continuity and feature-150 records from the new night, then match timestamped windows and float32 measurements against the same night's reference. Keep all readings, raw frames, reference logs and identifiers private. Windowed Bluetooth RMSSD/SDNN must not be relabeled as Samsung's daily sleeping-HRV summary or fed into production coaching before validation.
