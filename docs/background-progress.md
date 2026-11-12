# Durable progress processing

Issue: #52. This change builds on the incremental submission, evidence-manifest, and submission-recovery work in #48, #49, and #51.

After a submission is saved, Android JobScheduler owns nutrition recognition, workout recognition, and coaching. Leaving the Activity does not cancel those stages. Results are committed to SQLite and reloaded on resume; changes also refresh an already open screen. The composer remains usable during analysis. There are no notifications or foreground services.

The one-off job is persisted across ordinary process termination/reboot and reconciled on app open and boot. A single coroutine mutex prevents competing automatic processors. Existing immutable submission IDs, revision checks, and card primary keys prevent duplicate accepted facts/cards. A new submission or health capture during processing requests another pass. This provides idempotent local commits, not exactly-once external requests: termination after a provider response but before its SQLite commit can repeat that request.

## Health evidence

Foreground entry captures Health Connect evidence immediately, using the existing foreground read gate. The job never opens Samsung Health or invokes its screen reader. Samsung SDK recovery refresh and already archived recovery comparisons remain available independently.

Raw current-night HR samples remain in short-lived memory and can be consumed by a request once. SQLite staging stores only redacted sleep intervals, HR statistics, source coverage, and capture time. If that transient trace is unavailable, background Health Connect reading is attempted only when the supported background-read permission is granted. Otherwise the saved capture is used with `health_capture_mode=retained_statistics`, capture time, a raw-HR omission, zero included raw samples, and a separate count of samples underlying the statistics. Missing capture waits for foreground collection; missing metrics are not reported as newly measured values.

The optional background permission can be requested in Settings → Health Connect / connection and diagnostics. It uses the standard Health Connect permission UI and is never silently granted. It does not enable the separate Samsung screen-reader source.

## Retry and scheduling limits

Network, provider 429, and provider 5xx failures get at most three failed attempts per stage with Android exponential backoff. Access failures, invalid output, oversized evidence, and exhausted retries wait for an explicit retry. Retry reuses saved input and completed stages. Android can delay or stop ordinary jobs; no exact completion time is promised. Force-stop suspends work until the user opens the app again.

Schema 13 adds redacted health staging, durable stage/error state, and retry counts. It preserves the existing submission queue, converted facts, and saved coaching cards. Staging retains seven days plus dates still needed by pending feedback; it does not archive raw HR arrays or successful photo bytes.

## Validation

- JVM regression suite and debug/test APK builds.
- `BackgroundProgressTest`: disposable databases, fake provider; cancellation, bounded retries, manual retry, access failure, recognition reuse, health wait/resume, transient trace handling, schema-12 migration, and idempotent completion.
- `BackgroundProgressLiveTest`: opt-in (`live_background=true`), real Android JobService and provider with a synthetic submission and no resumed Activity. No owner health/history is loaded into the test database.
- `BackgroundProcessRestartTest`: opt-in two-phase check (`restart_phase=prepare` / `verify`). Run the persisted isolated job after the preparation process exits; verify retained-statistics fallback and saved feedback, then delete the test database. Never force-stop or clear owner data.

The comparison pilot in #50 is separate and is not started by this implementation.
