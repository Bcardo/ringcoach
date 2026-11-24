# Galaxy Ring Bluetooth HRV investigation

**Current status (November 14):** The passive overnight recorder captured a morning health transfer containing timestamped feature-150 HRV, heart rate, sleep, respiration, movement, skin temperature, stress and encoded blood-oxygen data. The decoder now consumes the complete transfer. Older HRV windows match the existing Samsung reference; the fresh night's windows still need a same-night reference. Multi-night reliability and independent operation without Samsung's companion remain unverified. The installed RingCoach production path still uses the SDK/screen readers described in [nightly recovery integration](nightly-recovery.md). See the [standalone research app](../research/bluetooth-probe/README.md).

[Issue #9](https://github.com/Bcardo/ringcoach/issues/9) tracks whether RingCoach can fetch fresh overnight HRV from the Galaxy Ring. Samsung's personal export includes `rmssd`, `sdnn`, `start_time`, and `end_time` in a dedicated HRV sidecar. The export can serve as a reference for a captured night.

## Capture a normal sync

1. Leave the Ring paired with Galaxy Wearable. On the Fold7 open **Settings → Developer options → Bluetooth → Enable Bluetooth HCI snoop log**. The Fold7's Simplified Chinese label is **启用蓝牙 HCI 信息收集日志**.
2. Select **Enabled** or **Full**, then switch Bluetooth off and back on. Let Galaxy Wearable reconnect and open Samsung Health to allow a normal sync. Capture a morning with fresh overnight readings to test HRV delivery.
3. With USB debugging authorized, capture a bug report outside the repository and analyze it locally. On Windows, run the following from the project root.

   ```powershell
   $capture = Join-Path $env:TEMP ('ringcoach-bugreport-' + [guid]::NewGuid().ToString('N') + '.zip')
   & (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') bugreport $capture
   python .\scripts\inspect_ring_bluetooth.py $capture
   ```

4. Turn off the HCI snoop setting when finished. Keep the capture local. It can include unrelated Bluetooth traffic and other device diagnostics.

[Android Bluetooth debugging guide](https://source.android.com/docs/core/connect/bluetooth/verifying_debugging) · [Reference Galaxy Ring capture workflow](https://github.com/jtmuller5/samsung-galaxy-ring-client#how-we-captured-the-protocol)

## Interpret the local report

The script opens only the full Bluetooth snoop member inside the ZIP. It looks for the Ring's identity message, counts channel 10 traffic, and checks for standard Bluetooth RR intervals when characteristic discovery is present. It prints packet counts, relative duration, and payload lengths. It omits raw values, Bluetooth addresses, and serial numbers.

- An empty `connections` list means the capture lacked the identity marker. Capture a reconnect or identify the Ring connection independently before using `--connection`.
- If the report has a rotated snoop member, pass its exact ZIP path with `--member` to check the earlier segment.
- Channel 10 traffic and `3f` replies establish that health history traversed the link. Confirm feature 150 using the record structure and matching timestamps and values from the same night's Samsung export.
- When the report cannot identify the standard heart-rate characteristic, a zero notification count leaves RR availability unresolved. The published Galaxy Ring sample has the Bluetooth RR flag clear. Check the proprietary health traffic for stored HRV.

Run `python -m unittest discover -s scripts -p test_inspect_ring_bluetooth.py -v` to exercise connection selection, fragmentation, standard RR flags, and ZIP handling with synthetic data.

[SamsungOpenRing health protocol notes](https://github.com/TheVellichor/SamsungOpenRing/blob/master/docs/RFC-SGR-001-Samsung-Galaxy-Ring-Protocol.md#93-health-feature-types) · [Bluetooth Heart Rate Service specification](https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/HRS_v1.0/out/en/index-en.html)

## Fold7 capture results

Matching the Samsung HRV CSV to the device-profile CSV attributed all HRV records from the comparison week to the Galaxy Ring. The personal export is a usable reference for matching a future Bluetooth capture.

The first USB bug report confirmed Full snoop mode and the enabled socket setting. Reports collected before and after a verified Bluetooth restart contained no full snoop attachment. Both embedded Bluetooth summaries decoded successfully and contained HCI commands and events only. The local port returned no BTSnoop header before or after the restart. These tests leave Bluetooth HRV delivery unverified.

If a full snoop file is absent from the USB report, Samsung's local SysDump workflow is another way to obtain a diagnostic archive for inspection. Keep the archive on the phone or in local temporary storage while looking for a full HCI log. [Samsung device log instructions](https://docs.samsungknox.com/admin/knox-platform-for-enterprise/troubleshoot/get-device-logs/)

The Fold7 exposed two Ring diagnostics under `Download/log/GearLog`. The Ring Health Provider log contained 8,540 JSON objects with positive `rmssd` and `sdnn` values and `start_time` and `end_time` fields. Deduplicating on those four fields left 4,127 records with start dates from October 31 through November 10, 2026 (UTC). The Ring Health Monitor log contained no RMSSD or SDNN fields. The provider log supplies another local reference for matching a future capture. The origin of the HRV calculation and a way to retrieve fresh records automatically remain unverified.

The standard SysDump ZIP was saved under the protected `/data/log` directory and was unreadable over USB. The combined Wearable dump option has not been tested on this setup.

## Overnight verification

Samsung documents sleep HRV as an input to the Galaxy Ring Energy Score. Use the next sleep session as the reference window. Whether the Ring also produces usable waking HRV remains open. [Samsung Galaxy Ring Energy Score](https://www.samsung.com/ae/support/mobile-devices/how-to-check-energy-score-with-the-galaxy-ring/)

1. Confirm HCI snoop is set to Enabled or Full before bed, restart Bluetooth, and let the Ring reconnect. Wear the charged Ring normally with the phone nearby and Bluetooth on. Use only the Ring as the Samsung sleep source for this test.
2. After waking, leave Bluetooth connected and check for the new sleep session and sleeping HRV in Samsung Health. Preserve the morning capture before restarting Bluetooth. Record the sleep window and check for new records against that window. Use the already-generated provider log as the pre-sleep baseline. Check whether it updated during normal sync before generating any new diagnostic dump.
3. If capture access works, compare Ring traffic from normal sync with timestamped HRV from the same session. A matching transfer identifies a candidate data path. Automatic retrieval is verified once RingCoach or a prototype reads fresh, Ring-attributed HRV during normal operation without a SysDump or manual export.

## November 11 follow-up

The owner reported a new Energy Score and sleeping HRV after wearing the Ring overnight. The Fold7 was reachable over USB and Bluetooth diagnostics showed the Ring connected to its official companion application.

Before requesting a new bug report, the shared Ring Health Provider diagnostic was still the November 10 file (8,205,861 compressed bytes). Parsing its JSON-encoded `binning_data` arrays reproduced the previous 4,127 unique `(start_time, end_time, rmssd, sdnn)` records. The file had not refreshed during ordinary overnight use.

A new USB bug report was captured locally. During the diagnostic collection, the shared Ring Health Provider file refreshed. The refreshed file contained 5,003 unique HRV records: all 4,127 baseline records plus 876 new records with November 11 UTC start dates, with no baseline records removed. These are overlapping multi-minute windows, not 876 independent beat intervals. This verifies fresh overnight HRV in the diagnostic reference, while the aggregation and units of Samsung Health's displayed sleeping HRV still require comparison. Exact readings and timestamps remain in local temporary storage.

Unlike the earlier reports, the new ZIP included both `FS/data/log/bt/btsnoop_hci.log` and `FS/data/log/bt/btsnoop_hci.log.last`. Both contain HCI ACL packets. Their encoded timestamp ranges cover approximately 20 minutes in total, so the presence of full logging does not establish preservation of the overnight transfer. The analyzer found four ATT connection ordinals in the current segment, no Ring identity marker in either segment, and no channel-10 messages on those four connections. It therefore cannot identify or decode an overnight HRV transfer from this capture. Capture timestamp timezone interpretation and traffic on other L2CAP channels remain unverified.

The standalone embedded Bluetooth summary is not a substitute for these full logs. Its AOSP decoding produced one ACL entry with an included length greater than its reported original length, which the strict inspector correctly rejected. The full snoop files parsed without truncated ACL packets. The four existing synthetic inspector tests passed.

Next, inspect a short normal Ring reconnect/sync while the phone is unlocked and preserve the capture promptly to limit rotation. Compare any identified health records against the fresh diagnostic reference. Do not treat diagnostic collection as proof that a production app can read HRV automatically.

Samsung's documented [EnergyScoreType](https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-energy-score-type/index.html) supports read access to Energy Score and source metadata. Its listed fields do not expose the sleeping HRV component. Treat an Energy Score SDK integration and automatic HRV retrieval as separate research tasks.

After the owner unlocked the Fold7, the current Energy Score and sleeping HRV were verified directly in Samsung Health. The displayed sleeping HRV does not equal the unweighted arithmetic mean of the new diagnostic RMSSD windows. Preserve the daily summary and windowed records as distinct measurements until Samsung's filtering and aggregation can be established. The diagnostic contained no `sleeping_hrv`, `sleep_hrv`, or `energy_score` field labels. Exact screen readings are retained only in the local research handoff.

## November 13 direct-protocol results

The previous negative probe was not a sufficient capability test. On this firmware the companion uses battery query `0b 0b 02` and receives `0b 0b 42 ...`; the older public example's `0b 0b 03` was silent. A standalone research application with its own granted Bluetooth permission received the battery response using message 2. Bonded connection, MTU 498, discovery of ten services, and Data-service CCCD subscription also succeeded. Characteristic reads returned status 2 (read not permitted); notification requests nevertheless work.

A bounded health request on channel 10, using client 1, message 3, a new sequence and a two-day time boundary, received six health notifications. Five were bulk-transfer fragments forming one 735-byte message. Strict decoding consumed the complete message and identified feature 148 (movement), feature 19 (stress), and feature 5 (device information). It contained zero feature-150 HRV records. The prototype sent no health/bulk acknowledgment, health preferences, pairing commands, or account association. This proves usable direct health-channel traffic alongside the official companion; it does not establish a complete replacement for Samsung's companion or fresh-night HRV delivery.

The installed companion's protocol code was inspected locally to resolve framing and measurement ambiguities. Decompiled vendor source and all captures remain private. Findings:

- Bulk messages use a six-byte header after the channel envelope: message ID, flag/window, and a four-byte little-endian chunk index. Assemble ordered chunks within one connection and direction before interpreting features. A `3f` frame alone does not prove a health record or HRV.
- Feature 150 has length-delimited records. HRV binning consists of 16-byte entries: float32 SDNN, float32 RMSSD, uint32 start time, uint32 end time, all little-endian. Time fields are integer seconds since 1990-01-01, converted to epoch milliseconds; interpreting them as float32 timestamps is incorrect here. Units and same-night reference matching still require captured HRV.
- The current companion supports P-256 ECDH session key exchange and AES-CTR data processing. Matching client-4 key-exchange packets were observed. This does **not** establish that client-1 HRV records are encrypted, or that encryption blocks HRV access. No companion secrets were extracted and no session rekeying was performed by the prototype.
- The generic identity acknowledgment differs from the older public sample. An optional hello responder is implemented from this phone's observed generic setup, but no hello reached the direct probe, so that path remains unverified. Do not replay old session tokens, user settings, or account fields.

The passive foreground recorder was tested with an actual Ring notification and a clean explicit stop. It runs in a separate research package/process, uses a visible notification, reconnects to the existing bonded Ring, and stops after 18 hours or 32 MiB. It writes local Ring notifications only and sends no protocol commands or cloud traffic. Its overnight behavior is still an experiment, not accepted collection reliability. Android termination or permission revocation can interrupt it.

Run the eleven synthetic capture/protocol tests with:

```powershell
python -m unittest discover -s scripts -p 'test_*ring*.py' -v
```

The tests cover fragmentation, connection separation, truncation, false HRV-byte matches, integer timestamps, HRV field order, privacy of summaries, and float32 reference comparison. The standalone APK build and Android lint pass. Issue #9 remains open until a new night's captured feature-150 windows match the corresponding private reference and the normal-operation retrieval path is demonstrated.

## November 14 overnight decoding

The preserved passive capture contains 769 notifications, including 618 health-channel notifications and ten complete bulk messages. Extending the strict parser for the observed blood-oxygen and fixed-field sleep records allows it to reach the later HRV field in the same transfer. The earlier report of zero decoded HRV reflected incomplete decoder support; it did not establish that HRV was absent. The extended decoder reports no bulk or supported measurement decoding errors.

The capture contains 921 distinct HRV windows. Of these, 95 older windows match the existing provider reference exactly after float32 normalization, including both timestamps and RMSSD/SDNN. The remaining 826 windows belong to the new wake date and overlap the captured sleep session. The existing reference ends on the preceding date, so these new windows remain pending same-night validation. Overlapping windows are not independent beat intervals or Samsung's daily sleeping-HRV summary.

`scripts/ring_measurements.py` additionally decodes heart-rate minute bins, movement windows, respiration windows, temperature windows, sleep session fields, stress fields, and raw sleep rows. Blood-oxygen optical blocks are counted but are not presented as saturation percentages. Raw R-R entries are retained privately with their flags; their units, timing and quality interpretation must be established before independent HRV calculation. Temperature calibration and units also need reference confirmation. Operational analyzer summaries contain counts only; personal values and arrays remain in ignored private storage.

One successful overnight capture demonstrates a usable passive path alongside the official companion. Packet arrival throughout a night does not prove uninterrupted connection, and this recorder's reconnect attempts and last disconnected status require further reliability investigation. No production APK replacement, accessibility change, pairing change, protocol acknowledgment or rekeying was performed during analysis. Seventeen synthetic tests pass, including the regression where blood oxygen and sleep precede HRV, truncated arrays, temperature/RRI separation and summary privacy.
