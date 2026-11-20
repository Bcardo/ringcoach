# Recovery collection reliability (#28)

## Screen-free collection

Screen navigation is retired. All coaching uses available Bluetooth session statistics, SDK/Health Connect context and retained history without opening Samsung Health. The old accessibility component is inactive and remains only to preserve the owner's existing configuration/binding. Its configured/bound status proves no new sensor reception or successful capture.

The collector's opt-in setting, foreground process, fresh heartbeat, connected/subscribed state, last packet, decoded transfer, dated archive coverage and successful coaching inclusion are separate evidence. None alone proves the others. Reconnection gaps and unknown/missing dates stay explicit. Same-night reference comparison is required before quantitative Bluetooth HRV validation; window counts are not independent beat counts or Samsung daily sleeping HRV.

## Current evidence and open work

The November 20 audit found fresh live-origin sleep/HRV on four distinct new wake dates with reception on each wake date and corresponding completed morning jobs. One intervening date lacks a live sleep record, and the collector was reconnecting when checked. The cause of that gap and uninterrupted delivery are unverified. Imported November 14 research data is separate and does not add acceptance nights. No forced scheduler run or synthetic replay adds ordinary-use evidence.

Follow #9 for matching timestamped windows/units to a private same-night reference; follow #28 for gap recovery, idle/reboot coverage and source/coaching inclusion. Keep both open while their remaining acceptance items are unverified. The 14-day comparison pilot (#50) has not started.

## Physical checks

Use `scripts/run_android_checks.ps1`: it warms the app, uses `am instrument --no-restart`, verifies test success and checks accessibility configuration before/after. Never fall back to the force-stopping runner. UiAutomation requires `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`. Recheck configured/bound legacy service independently after APK replacement; do not attempt a fresh screen capture or silently toggle it.

`ScreenFreeRecoveryOwnerTest` verifies preparation and foreground refresh stay outside Samsung Health beyond the former retry cooldown, without sending a model request or editing Journal facts. Disposable storage/request regressions verify retained vendor history, separate Bluetooth evidence and missing values.

Existing bounded `recovery-reliability` preferences retain lifecycle and coaching-presence metadata only. New capture observations are no longer produced. Operational logs and public tracking exclude readings, UI text, sample arrays, model responses, photos, credentials and identifiers.

## Acceptance

Record distinct new wake dates under normal use, freshness, gaps, relevant collector state, ordinary idle/battery behavior, any intervention and successful inclusion in subsequent coaching. Verify the installed screen-free update does not navigate Samsung Health. A real reboot is a separate scenario; no force-stop test is authorized on the owner's package. Repeated same-day captures, historical backfill, synthetic tests and manual retry do not replace ordinary-use acceptance.
