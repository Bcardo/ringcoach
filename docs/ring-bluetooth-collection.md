# Direct Ring sleep collection

RingCoach can keep a passive Bluetooth connection to the already bonded Galaxy Ring and receive its health transfers alongside Samsung's official companion. This integration is experimental: the research capture demonstrated overnight reception, but production multi-night reliability and same-night HRV reference validation are still pending.

## Enable and use

In Settings → account and health connections, choose **开启戒指睡眠采集**. Android requests Nearby Devices (`BLUETOOTH_CONNECT`) and notification access. Collection is off until enabled; the existing Samsung Health and accessibility permissions are independent.

Keep the charged Ring near the phone, Bluetooth on, the **RingCoach sleep collection** notification active, and Samsung's official companion available. Wear the Ring normally. Allow Samsung's normal morning sync: the recorder listens for transfers and does not issue history requests or replace the companion. No Samsung Health screen navigation or phone unlock is required by the Bluetooth collector itself.

The collector runs as a `connectedDevice` foreground service in a separate process. It reconnects with bounded backoff, serializes discovery/subscription setup, retries temporary GATT write-busy responses, and discards incomplete bulk messages when the connection changes. It sends only the required notification subscription, with no health acknowledgment, profile/account write, rekeying or deletion command.

Opt-in persists through normal process recreation, app update and reboot. Android may delay or prevent restart; opening RingCoach resumes an enabled collector when permitted. The UI distinguishes enabled state, recently reported connection/subscription, last received data and dated sleep records. None alone establishes uninterrupted coverage. Stop through Settings or the notification; this disables future automatic collection. No battery exemption, pairing reset, accessibility toggle or permission grant is performed silently.

## Local archive and coaching

Decoded timestamped sleep sessions, windowed RMSSD/SDNN, minute heart-rate bins, respiration, movement, temperature and stress are retained in app-private `ring-bluetooth.db`, capped at 35 days and 200,000 points. Repeated records replace the same source/metric/time interval. A hash of the bonded device identifier is used only for local source separation and is omitted from coaching requests. This collector does not retain raw notifications, raw sleep R-R arrays or encoded optical payloads in its production archive.

Each session is associated with the wake date from its end timestamp in the request timezone. Coaching includes at most seven wake dates of per-session statistics, sample/rejection counts, coverage minutes, reception times, source and validation labels. It does not send raw sample arrays or identifiers. Overlapping sessions remain separate, and multiple sources must not be added together. The request manifest identifies target-date session and HRV-window counts.

A new current-date sleep session lasting at least 90 minutes with overlapping usable HRV queues one morning request, even if the session and HRV arrive in separate transfers. Historical transfers, imported research captures and duplicate notifications do not create new morning requests. A separately authorized review of an imported night can be queued with `preserved_ring_sleep_capture` provenance. This is not fresh-night production evidence.

The persisted network-constrained job waits at least two minutes for the morning transfer to settle; Android chooses its execution time. It uses the saved model credential and plans, completed nutrition/workout facts, available Samsung daily summaries and Ring statistics to generate the usual editable advice card. It does not create a journal submission, acknowledge unrelated progress, edit the saved routine, or mark a workout completed. Pending recognition is allowed to finish first; missing credentials block advice while collection continues. Network/provider retry is bounded to three failures, and Settings provides manual retry. A saved card prevents replay of a completed date, including after a process restart. A process death during an in-flight model request can require another network request, but the saved card ID remains unique.

Progress-triggered and manually prepared coaching requests also include the Ring summary. If Health Connect cannot supply a background capture, available dated Ring sleep permits progress coaching with Health Connect explicitly labeled unavailable.

## Interpretation

- Windowed RMSSD/SDNN are separate from Samsung's daily `sleeping_hrv`. They are overlapping windows, not independent heartbeats. The model receives pending-reference/unit labels and cannot substitute them into daily vendor metrics, compute a readiness score or HRV z-score, or trigger quantitative recovery rules from them.
- Minute heart rate is not vendor resting heart rate. Sleep-session span is not actual time asleep or sleep-stage scoring. Sensor temperature is not core temperature; calibration and units still need validation.
- Encoded blood-oxygen data and candidate raw R-R entries are not presented as valid oxygen percentages or independent beat intervals.
- The coach may use the available evidence to discuss rest and adapt the saved fitness plan conservatively; experimental Bluetooth values alone are not a basis for increasing intensity or diagnosing fatigue.

## Checks

Build: `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest`.

Run disposable on-phone storage/coaching tests with `scripts/run_android_checks.ps1 -Classes 'com.bcardo.ringcoach.RingBluetoothIntegrationTest,com.bcardo.ringcoach.NightlyRecoveryStoreTest,com.bcardo.ringcoach.BackgroundProgressTest'`. The wrapper always uses `am instrument --no-restart` and checks accessibility enablement. Opt-in arguments can be passed through its `-Arguments` hashtable; it never falls back to force-stop.

`RingBluetoothOwnerSetupTest` is excluded from routine operation unless `ring_owner_setup=true` and an action-specific flag are supplied. It can verify/import an owner-preserved private capture, request collection permissions through Android dialogs, or explicitly queue review of an imported sleep. It requests `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` for UI automation. Never include personal captures in test assets or commits. Passing a replay test is not overnight acceptance.

Verify distinct new wake dates under ordinary use before accepting nightly reliability. After APK replacement, check Samsung reader configuration, binding and a fresh successful capture independently; a bound service alone does not prove fresh screen data.

Android references: [background BLE communication](https://developer.android.com/develop/connectivity/bluetooth/ble/background), [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device), [Bluetooth GATT](https://developer.android.com/reference/android/bluetooth/BluetoothGatt), [GATT busy status](https://developer.android.com/reference/android/bluetooth/BluetoothStatusCodes#ERROR_GATT_WRITE_REQUEST_BUSY).
