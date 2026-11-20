# Nightly recovery without screen capture

RingCoach uses the opt-in [direct Ring Bluetooth collector](ring-bluetooth-collection.md), saved recovery history, and available Samsung SDK/Health Connect data for all coaching. Opening RingCoach, submitting progress, refreshing recovery and preparing advice never launch Samsung Health or wait for a screen read. No screenshot or accessibility setup is required.

## Collection and setup

Enable Ring sleep collection in Settings and allow the Nearby Devices/notification permissions through Android's dialogs. Keep the existing Ring pairing, charged Ring, phone Bluetooth and visible collection notification available. Samsung's official companion still participates in normal transfers; this is not an independently verified replacement for the companion. RingCoach does not navigate Samsung Health's screens.

Samsung Energy Score remains an optional SDK source. The development build requires the owner-downloaded AAR described in `app/libs/README.md`, Samsung's development read mode/agreement, and an explicit Energy Score read grant. The persisted job polls roughly every six hours; Android controls execution. Foreground refresh and progress submission also refresh SDK data. SDK access is distinct from screen reading.

Existing Health Connect sleep/heart-rate grants supply additional sessions, stages and HR context. Source failures, stale dates and missing metrics remain explicit. Absence of a vendor daily HRV summary does not block coaching or become a zero.

## Retired screen reader and retained history

The former Samsung Energy Score page navigator, date/page parser, capture timeouts, retry/cache triggers, accessibility setup button and coaching capture waits have been removed. The original `SamsungHealthScreenReader` component remains inactive solely to preserve existing owner-enabled Android configuration/binding through updates. It cannot retrieve window content and never reads windows, opens an app or performs accessibility actions. No accessibility setting is toggled.

Previously saved daily Samsung sleeping-HRV/Energy Score observations, source labels, verification dates, revision audit and coach cards remain intact. Their screen sync status is presented as `retained_history_only`; it is not a new successful capture. New daily vendor HRV is no longer collected by this adapter. Bluetooth RMSSD/SDNN remain separate experimental window statistics and never fill the vendor `sleeping_hrv` field.

## Coaching

New current-date Bluetooth sleep plus usable overlapping HRV queues one morning update per wake date. Progress recognition and coaching retain their existing persistent queue and retry behavior. Every advice path includes a dated Ring summary, available health context, saved plans and confirmed progress. Missing daily Samsung metrics do not trigger a screen workflow. The prompt instructs the coach to answer from available evidence without asking for screenshots or accessibility activation.

The request manifest reports Ring session/window counts, source coverage, missing vendor summaries and `screen_capture_mode=retired`. Historical advice remains unchanged. See [request context and retention](coaching-request-context.md).

## Verification and remaining acceptance

The November 20 read-only audit found live-origin sessions received on four distinct new wake dates, each with overlapping HRV and other sensor points, and completed morning jobs for each date. A gap remains between those nights; the collector was reconnecting at the audit. These observations establish real fresh-date reception, not uninterrupted reliability or verified measurement accuracy. Same-night RMSSD/SDNN reference comparison and the missing-night cause remain open under [#9](https://github.com/Bcardo/ringcoach/issues/9) and [#28](https://github.com/Bcardo/ringcoach/issues/28).

Storage tests cover historical retention and retired status. Request tests cover Bluetooth evidence when vendor daily summaries are absent. `ScreenFreeRecoveryOwnerTest` is opt-in (`verify_screen_free=true`): it refreshes/prepares existing facts in memory, observes beyond the old retry cooldown without reading UI text, and sends no model request or journal update. Run physical checks only through `scripts/run_android_checks.ps1`; never force-stop the real app or suppress accessibility services.
