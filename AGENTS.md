# Physical-device checks

The owner's Fold7 has an explicitly enabled legacy accessibility service. Screen capture/navigation is retired: the component is now inactive and retained only to preserve the existing setting and binding during updates. Do not reintroduce Samsung Health screen capture, screenshot requirements, or coaching waits for that service.

- Standard `am instrument` force-stops its target. Android can remove accessibility enablement on force-stop. Use `scripts/run_android_checks.ps1`, which starts the app and uses `am instrument --no-restart`, and checks that enablement survives. If no-restart is unavailable or fails, report the failure rather than falling back to a force-stop.
- When using `UiAutomation`, request `UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`. Default UiAutomation suppresses other accessibility services.
- Do not silently enable/toggle accessibility or alter other phone permissions. Use a disposable package/emulator for deliberate force-stop tests unless the owner explicitly requests that test on the real app.
- Recheck legacy service configuration/binding after APK replacement. Its binding is compatibility evidence, not fresh data evidence. Verify Bluetooth reception, dated archive coverage and coaching inclusion independently. A missed callback cannot establish when or why a setting changed.
- Keep personal readings, screen dumps, photos, device identifiers and credentials out of commits/issues. Operational diagnostics must exclude UI text and HR sample arrays.
- Multi-night acceptance requires distinct new wake dates under normal use. Historical backfill, repeated same-day captures and forced scheduler runs do not substitute for fresh-night reliability evidence.

# Recurring processing failures

- Track recurring stuck submissions under GitHub issue #52 and link the confirmed cause-specific issue (currently #56 for dated status/request budget and #57 for workout validation). Follow docs/reliability-incidents.md.
- Record the failing stage, specific error code, reproduction class, fix, regression check, installed/source status and recovery outcome. Distinguish observations from suspected causes.
- Do not close a reliability issue solely because a manual retry or synthetic test passed. Keep ordinary-use recurrence acceptance explicit.
- Public tracking and operational logs must exclude Journal/UI text, readings, model responses, sample arrays, photos, credentials and identifiers.
