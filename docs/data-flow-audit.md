# Recovery verification and data flow audit

## What is and is not verified

The production path is Ring → Samsung's companion/Health apps → RingCoach readers. RingCoach does not implement a verified direct Bluetooth sleep/HRV history reader. Bonded-device connection and service discovery succeeded in research, but the short notification subscription produced no usable health-history transfer. Available captures did not identify and match an overnight HRV transfer. Direct Bluetooth retrieval remains open in issue #9.

Energy Score SDK reads, exact daily HRV screen capture, recovery persistence, recognition and a real coaching request were verified on the Fold7. A background Energy Score job was forced successfully. This proves those paths can work, not unattended delivery across multiple fresh nights or after reboot, idle, power saving, permission changes or Samsung UI changes. HRV capture requires an unlocked foreground session; only Energy Score has a background job. SDK/screen records agree on tested dates, but Samsung provenance alone does not prove hardware attribution for every record.

## Collection, phone storage and model submission

There is no RingCoach cloud backend. Normal model requests go directly from the phone to the saved Qwen endpoint. “Saved locally” and “sent to the model” are different destinations.

| Data | Phone/local retention | Sent for analysis/coaching |
| --- | --- | --- |
| Submitted journal text and corrections | Immutable dated submissions plus cumulative journal/extraction sources in private SQLite | Nutrition sees dated diet/mixed text; workout extraction sees mixed text and exercise references; coaching receives seven calendar days of journal context |
| Submitted food photos | Compressed JPEGs in private SQLite | Nutrition receives photos for the analyzed date with the text. The final coaching call receives the nutrition result, food summary, assumptions and provenance, not the images |
| Unsubmitted attachments / legacy meal drafts | Retained privately | Not included as consumed food merely because they exist locally |
| Nutrition estimates, evidence, questions and assumptions | Private SQLite | Included in current seven-day coaching context; no second addition of photo estimates for unified days |
| Base routine, diet plan and coaching instructions | Private SQLite | Supplied to coaching; instructions become system-message content |
| Workout observations and imported references | Private SQLite | Latest applicable exercise baselines and recognized completed/partial performance, with statuses, reach coaching |
| Energy Score and exact sleeping HRV | Dated records and sync state in separate private recovery SQLite | Seven calendar days of values, sources, precision/interpretation and sync status reach coaching |
| Health Connect sleep | Read on demand; summary archived with each saved coach card | Session start/end, wake date, session duration and minutes per sleep stage. Individual stage intervals and their sequence are reduced to totals |
| Health Connect heart rate | Raw records read into memory on demand; raw JSON only if the owner explicitly exports | Seven-day sample count/min/max/mean and source metadata. Individual timestamps, per-night averages and the raw sample stream are not sent |
| Health Connect RMSSD, if available | Read on demand; summary archived | Sample count/min/max/mean. No computed baseline or z-score. The tested configuration returned no HC RMSSD records; vendor daily HRV is a separate metric |
| Health Connect skin temperature, if available | Read on demand; summary archived | Delta count/min/max/mean and count of records with a baseline. Absolute baseline values and individual deltas/timestamps are omitted. The tested configuration returned no records |
| Health Connect reviewed export | Written only to the location selected by the owner | Not automatically uploaded. Its raw fields are broader than the normal coaching summary |
| Prior coach cards and exact input snapshots | Private SQLite, browseable in History | Cards are not automatically resent as conversation memory. New coaching uses supplied plans, raw journals, recognized progress and current health summaries |
| API credential | Android Keystore-encrypted private storage | Used only for request authorization, not included in model context, SQLite plans or settings imports |
| Bluetooth snoops, provider diagnostics, raw RMSSD/SDNN windows and personal research exports | Owner's private phone/PC research files; not production app ingestion | Not automatically fed into RingCoach or Qwen. Raw windows are not substituted for Samsung's daily sleeping HRV |

## Limits and remaining work

- Current journal/nutrition/recovery coaching context uses seven calendar days. Older local records are retained but not all are submitted on every request. Exercise baselines/latest recognized performance have their own applicable-date queries.
- Health Connect data is freshly read for coaching, not continuously copied as a raw historical database. If the source later removes records, the app retains prior coach snapshots, not a full raw backup.
- Missing or unauthorized metrics remain explicit. There is no current collector for Samsung sleep score, vendor resting heart rate, respiration, SpO₂, steps, activity calories or temperature baseline. Seeing a number in Samsung Health does not mean RingCoach collected it.
- No verified multi-night automatic-delivery acceptance run has been completed. Foreground HRV capture depends on Samsung's tested Chinese layout, resource IDs and a one-hour successful-capture cache. Background timing is inexact.
- Nutrition photo estimates and composition assumptions are model estimates, not validated measurements. Additions/corrections/deduplication were tested synthetically and a reported real failed update was recovered, but broad food-image accuracy remains unmeasured.
- The seven-day heart-rate aggregate loses nightly trends. Stage sequence and skin-temperature baseline values are also intentionally reduced by the current serializer. Adding these to coaching would require a deliberate schema and test update.

## Code trace

- `HealthConnectProbe.coachingSnapshot` reads the four supported Health Connect types; `CoachingTelemetry.summarize` reduces them for coaching.
- `SamsungNightlyReader.refresh`, `NightlyRecoveryStore.snapshot` and `SamsungHealthScreenReader` handle vendor recovery values.
- `JournalStore.readCoachingContext` and `CoachingContext.toJson` select local progress and plans.
- `NutritionExtractor.request` attaches images; `CoachViewModel.prepare` adds health context; `QwenClient.request` builds the actual coaching message and removes the internal submission ID.
- `TelemetryExport` is the separate owner-reviewed raw-export path, not the automatic coaching payload.
