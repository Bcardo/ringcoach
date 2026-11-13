# Recovery collection reliability (#28)

The HRV path has three separate states: the owner has enabled the accessibility service, Android has bound it, and a date-verified Samsung Health capture has succeeded. A current Energy Score from the SDK proves none of these HRV states.

## Fixes

Samsung can resume a different detail page or a scrolled home dashboard when the Energy Score action is launched. Body composition shares Today/date controls with Energy Score. Date controls alone must never identify the metric screen. The reader recognizes the Energy Score container, backs out of the known body-composition page, brings the dashboard's Energy Score card into view, and selects only one visible pager page. Offscreen pages and transition ties cannot be assigned to a wake date.

When the service binds after Activity startup, a foreground observer requests the missed capture. A successful target-day capture is cached even if historical backfill later fails. Automatic retries have a short cooldown; explicit Settings refresh bypasses the success cache. Coaching may await an already active capture, but the background job never starts Samsung UI.

Capture completion returns to RingCoach only while Samsung Health is still the active app. A timeout or interruption must not pull the owner back over another app they chose to use.

`recovery-reliability` preferences keep at most 64 lifecycle/capture observations and 30 days of collection/coaching-presence metadata. They record enablement, connection, boot count, APK update time, capture phase, limited view identifiers/bounds and date-specific presence flags. They contain no UI text, readings, HR samples, photos, credentials or coach prose. These records show when a state was observed, not the exact time or cause of an unobserved disablement.

## Testing without disabling the reader

The default Android instrumentation launch force-stops its target; Android's accessibility manager removes enabled services on a force-stop. UiAutomation also suppresses other accessibility services unless configured otherwise. These are plausible development-related causes, not proof of the owner's original disablement.

Use `scripts/run_android_checks.ps1` for routine isolated tests. It opens the app, requires a running process, uses `--no-restart`, verifies test success and checks enablement before/after. Never fall back to the default runner. Tests using UiAutomation set `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`. Install test artifacts before warming the target process. Recheck service binding after replacement; an enabled-but-stuck binding is not a successful capture.

References: [instrumentation startup](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActivityManagerService.java), [force-stop accessibility handling](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java#938), [UiAutomation flags](https://developer.android.com/reference/android/app/UiAutomation#FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).

## Three-night acceptance

The recovery capture/repair session is a baseline check. It is not three-night acceptance. Begin assessment with the next fresh wake date after deployment, using the owner's normal submissions without another logging workflow.

For three consecutive new wake dates, compare the date-verified Samsung HRV and SDK Energy Score with the retained record and the next successful coaching input. Record first-observed/last-verified time, metric/source gaps, capture failures, any manual intervention and successful response persistence. Historical backfill and repeated same-day requests do not add nights. An intervention must be reported rather than counted as unattended success.

Collect ordinary idle/battery behavior before claiming background timing reliability. A real reboot and deliberate force-stop are separate scenarios; they are not inferred from an app update or Activity reopen. Keep #28 open until its remaining acceptance evidence exists. The separate 14-day comparison pilot (#50) has not started.
