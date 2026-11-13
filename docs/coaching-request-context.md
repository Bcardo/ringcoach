# Detailed evidence and request coverage

Each new coaching request has a versioned `request_manifest`. Today and an archived advice card expose **查看本次依据与覆盖**: the request date, progress/recovery periods, available current-night sessions/stages/HR samples, source-specific observed-day counts, missing current-day recovery, omitted optional context and serialized request size. Old cards remain unchanged and have no fabricated retrospective manifest.

## Current-night evidence

`telemetry.current_night` contains all completed Health Connect sleep sessions whose end/wake date matches the request date. A record's end offset determines its wake date when supplied; otherwise the request timezone applies. This selection may include naps. It does not identify one vendor-defined main night.

Sleep sessions preserve original stage interval order, timestamps, offsets and available source/record/device metadata. Sessions and overlapping sources stay separate. Session duration is not inferred time asleep. Provider identity/device model does not prove Galaxy Ring attribution.

Timestamped HR samples are selected from the union of those half-open sleep intervals, clipped to the Health Connect read window. Values and timestamps are unchanged. Each source record remains separate, with sample counts, first/last sample times and min/max/mean. Sample membership references sleep sessions by time; it does not assert common device provenance. When every sample in a record has the same session references, the references are stored once at series level; otherwise they remain per sample. This is lossless packaging, not downsampling. Sparse source samples are not treated as a continuous trace, and overnight minimum HR is not vendor resting HR.

The seven-day Health Connect summary remains available separately. Reads include overlapping source records and summaries count only samples inside the read window. Empty, denied, unsupported and failed sources remain explicit. Pagination treats an empty completion token as completed and rejects repeated tokens rather than looping or presenting a failed partial read as complete. This follows the [Health Connect raw-data read guidance](https://developer.android.com/health-and-fitness/health-connect/read-data).

## Historical and progress context

The previous 30 calendar days, excluding the target date, use the same canonical Energy Score/sleeping HRV policy as History. Rows retain dates, missing/conflict status, value/unit, source/measurement definition, verification time, revision and source record reference. SDK/screen readings do not become two nights. Source/definition coverage is separate; unknown hardware attribution stays unknown. A 30-day query is not 30 valid observed nights. Baselines, eligibility, z-scores and composite readiness are not computed in this slice (#35).

Current converted food facts, assumptions, applicable exercise references/observations and source-linked supplement events remain available without old photo bytes. Historical food uses daily values, kind, coverage, food count and source submission references; older journal prose and per-item food details are explicitly omitted. Supplement events are provided once in their dedicated seven-day section. Current journal prose and relevant same-day prior advice are optional context. Advice remains distinct from confirmed consumption/performance.

The saved routine/diet/instructions get a content-addressed snapshot ID. This versions the Settings text used by that request, not historical effective-date plans or confirmed model decisions. Inspectable plan memory remains #40. The full durable sleep archive, historical sleep timing comparisons and wider source sync work remain #30; this delivery does not close all of #34's dependencies.

## Size and persistence boundary

The budget is 128 KiB for the actual serialized Qwen request, including system text, user JSON, schema and manifest. The manifest's byte count is stabilized against that serialization. Optional prior advice and a duplicate current-day raw journal copy can be dropped, with explicit omission entries. Current converted facts, the triggering submission and current-night evidence are not silently truncated. If the required payload still exceeds the limit, no request is sent and the existing retry flow keeps the submission pending.

`PreparedCoaching.request` holds the actual request only in memory. Successful cards save a separate retained context: sleep intervals, HR statistics/coverage and a manifest with the actual request byte count and SHA-256. HR `samples` arrays are removed. `JournalStore.saveCoachCard` applies the removal again at the SQLite boundary. The archived context is clearly labeled as retained facts/coverage rather than a replayable exact trace payload. API/model failures do not persist the request; retry rereads the source. Source history that is later unavailable cannot be reconstructed from a saved card. No raw trace logging or trace cache is added. Existing old cards had aggregate HR, not these transient sample arrays.

Actual model usage tokens, provider latency and monetary cost are not captured by this slice. A serialized byte count is not a token count or cost estimate.

## Freshness and automation

The manifest checks target-date vendor metrics rather than trusting an old source-level `available` flag. It records the latest retained HRV date, last screen check, and whether Android has the accessibility service configured and connected. Today explains missing HRV and a disabled/disconnected reader. These warnings do not enable accessibility automatically or imply unattended capture works.

A read-only owner audit can package current data in memory with `CurrentContextOwnerAuditTest` (`-e audit_context true`) and report counts without sending another request or saving another card. Synthetic `DetailedCoachingContextTest` and `DetailedCoachingStoreTest` exercise exact request preservation, overlap/offset behavior, omission budgets, SQLite trace removal and a live synthetic request (`-e live_detailed true`). Personal audit artifacts stay under ignored `private/`.
