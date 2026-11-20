# Recovery verification and data flow audit

## What is and is not verified

The production collector passively subscribes to the already paired Ring alongside Samsung’s official companion. Live sleep and overlapping HRV were received on four distinct fresh wake dates; a missing night, reconnect behavior and same-night reference validation remain open under #9/#28. All coaching paths use dated Bluetooth summaries plus retained history and available SDK/Health Connect context. Samsung Health screen navigation and parsing are retired.

Energy Score remains an optional SDK read. Previously captured Samsung daily HRV stays in history, with its source and verification date. The legacy accessibility component is inactive and retains only configuration/binding compatibility; it cannot retrieve windows. No screenshot or screen capture is required for coaching. Fresh reception, source accuracy, continuous coverage and successful coaching inclusion remain distinct checks.

## Collection, phone storage and model submission

There is no RingCoach cloud backend. Normal model requests go directly from the phone to the saved Qwen endpoint. “Saved locally” and “sent to the model” are different destinations.

| Data | Phone/local retention | Sent for analysis/coaching |
| --- | --- | --- |
| Submitted journal text and corrections | Immutable dated submissions plus cumulative journal/extraction sources in private SQLite | Nutrition/workout extraction sees dated mixed text. Coaching preserves the triggering submission and converted facts; older raw journal copies are omitted, and current-day prose can be omitted explicitly for size |
| Submitted food photos | Temporary compressed JPEGs pending successful conversion; then app-private bytes are released, retaining a fingerprint/provenance | Nutrition receives only the pending turn's new photos, prior converted facts and text context. Coaching receives the resulting facts/estimates, not the images |
| Unsubmitted attachments / legacy meal drafts | Retained privately | Not included as consumed food merely because they exist locally |
| Nutrition estimates, food facts, evidence, questions and assumptions | Versioned turn results and confirmed daily food state in private SQLite | Current-day converted facts remain complete; older days in the seven-day coaching window use daily values, kind, coverage and source references. Item-detail omissions are listed |
| Supplement events | Source-linked dated events in extraction results and confirmed daily state, separate from coach prose | Seven calendar days of actual/planned/skipped/retracted events, explicit unknowns and food links; saved Settings text is the only standing-plan source. Nutrients are counted through food facts once. See [supplement feedback](supplement-feedback.md) |
| Base routine, diet plan and coaching instructions | Private SQLite | Supplied to coaching; instructions become system-message content |
| Workout observations and imported references | Private SQLite | Latest applicable exercise baselines and recognized completed/partial performance, with statuses, reach coaching |
| SDK Energy Score and retained historical daily sleeping HRV | Accumulating dated observations, source coverage, verification times and correction/deletion revisions in separate private recovery SQLite schema 2 | Seven-day source observations plus canonical daily comparisons for the previous 30 days excluding the target date; missing/valid counts and source boundaries remain explicit, without claiming a baseline |
| Health Connect sleep | Read on demand; target-date intervals and seven-day summaries retained with new coach cards | Target-date sessions preserve stage intervals, order, offsets and source metadata; historical sessions have timing/stage totals. Overlapping sources are not merged into one night |
| Health Connect heart rate | Read into memory on demand; new coach cards retain statistics/coverage, never sample arrays | Seven-day statistics plus unchanged timestamped samples within target-date sleep-session intervals reach the current request. Raw sample arrays are removed from saved context at producer and SQLite boundaries; retry rereads the source |
| Health Connect RMSSD, if available | Read on demand; summary archived | Sample count/min/max/mean. No computed baseline or z-score. The tested configuration returned no HC RMSSD records; vendor daily HRV is a separate metric |
| Health Connect skin temperature, if available | Read on demand; summary archived | Delta count/min/max/mean and count of records with a baseline. Absolute baseline values and individual deltas/timestamps are omitted. The tested configuration returned no records |
| Health Connect reviewed export | Written only to the location selected by the owner | Not automatically uploaded. Its raw fields are broader than the normal coaching summary |
| Prior coach cards and input snapshots | Private SQLite, browseable in History; no photo bytes or new HR sample streams | Optional same-day advice is labeled as advice. New snapshots retain facts, coverage, plan-text version and actual request digest/size, and explicitly cannot replay transient traces |
| API credential | Android Keystore-encrypted private storage | Used only for request authorization, not included in model context, SQLite plans or settings imports |
| Research-only Bluetooth snoops, provider diagnostics and personal exports | Owner's private phone/PC research files; not production app ingestion | Not automatically fed into RingCoach or Qwen. Raw windows are not substituted for Samsung's daily sleeping HRV |
| Direct Ring Bluetooth | App-private bounded archive of dated session/sensor points; no raw optical/R-R payload storage | Up to seven wake dates of separate per-session statistics, counts, reception dates and pending-validation labels; never substituted into vendor daily HRV |

## Limits and remaining work

Incremental daily nutrient totals now come from local sums of validated food facts, with unknown nutrients preserved and new supplement food links checked. Health Connect preparation follows the resumed Activity lifecycle and repeats reads interrupted by app switching. See [progress recovery](progress-recovery.md) for exact retry behavior and remaining background-process limits.

- Progress/supplements and on-demand Health Connect context use seven calendar days. Canonical recovery comparisons use the preceding 30 days; each series reports actual observed dates/counts, not an assumed complete month. Exercise baselines/latest recognized performance have their own applicable-date queries.
- Schema 12 queues each new incremental submission atomically with its journal/nutrition job. Each submission gets a separately generated and saved feedback card after recognition; historical dates and failed/restarted work remain queued. Existing schema-11 cards retain their previous covered submissions. The model gets the triggering submission plus latest confirmed date context, including corrections already processed during backlog recovery.
- Health Connect data is freshly read for coaching, not continuously copied as a raw historical database. If the source later removes records, the app retains prior coach snapshots, not a full raw backup.
- Missing or unauthorized metrics remain explicit. There is no validated collector for Samsung sleep score, vendor resting heart rate, SpO₂ percentages, steps, activity calories or temperature baseline. Bluetooth respiration and sensor-temperature statistics remain experimental. Seeing a number in Samsung Health does not mean RingCoach collected it.
- Fresh Bluetooth reception is observed on multiple dates, but a missing night and reconnect behavior remain unresolved. Uninterrupted reliability and same-night numerical accuracy are not accepted; Android background timing remains inexact.
- Nutrition photo estimates and composition assumptions are model estimates, not validated measurements. Additions/corrections/deduplication were tested synthetically and a reported real failed update was recovered, but broad food-image accuracy remains unmeasured.
- Confirmed schema-10 photo results are converted to a clearly labeled aggregate food fact before image release. Unresolved/unprocessed legacy images remain retryable; no original gallery file is deleted. After release, old visual detail cannot be re-inspected unless the owner attaches it again.
- Target-date stage sequence and timestamped HR now reach coaching transiently. Wider durable sleep history, historical nightly HR comparisons and skin-temperature baseline values remain incomplete. See the [detailed request contract](coaching-request-context.md), including payload budgets and exact persistence limits.

## Code trace

- `HealthConnectProbe.coachingSnapshot` reads the four supported Health Connect types; `CoachingTelemetry.summarize` reduces them for coaching.
- `SamsungNightlyReader.refresh` and `NightlyRecoveryStore.snapshot` provide SDK and retained vendor history. `RingBluetoothStore.snapshot` provides separate per-session statistics; the legacy accessibility component is inactive.
- `JournalStore.readCoachingContext` and `CoachingContext.toJson` select local progress and plans.
- `NutritionExtractor.request` attaches images; `CoachViewModel.prepare` adds health context; `QwenClient.request` builds the actual coaching message and removes the internal submission ID.
- `TelemetryExport` is the separate owner-reviewed raw-export path, not the automatic coaching payload.
