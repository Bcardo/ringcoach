# Physical-device checks

The owner's Fold7 uses an explicitly enabled accessibility reader to capture Samsung's daily sleeping HRV. Preserve its setting and binding during routine development.

- Standard `am instrument` force-stops its target. Android can remove accessibility enablement on force-stop. Use `scripts/run_android_checks.ps1`, which starts the app and uses `am instrument --no-restart`, and checks that enablement survives. If no-restart is unavailable or fails, report the failure rather than falling back to a force-stop.
- When using `UiAutomation`, request `UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`. Default UiAutomation suppresses other accessibility services.
- Do not silently enable/toggle accessibility or alter other phone permissions. Use a disposable package/emulator for deliberate force-stop tests unless the owner explicitly requests that test on the real app.
- Recheck the reader after APK replacement. Distinguish configured, connected, and successful fresh capture; none alone proves the others. A missed callback cannot establish when or why a setting changed.
- Keep personal readings, screen dumps, photos, device identifiers and credentials out of commits/issues. Operational diagnostics must exclude UI text and HR sample arrays.
- Multi-night acceptance requires distinct new wake dates under normal use. Historical backfill, repeated same-day captures and forced scheduler runs do not substitute for fresh-night reliability evidence.
