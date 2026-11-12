# Nightly recovery and progress updates

RingCoach reads Energy Score using Samsung Health Data SDK 1.1.0. A persisted Android job polls approximately every six hours; Android chooses the actual run times. Opening RingCoach or submitting progress also refreshes the data. The reader reconciles a seven-calendar-day window, including updates and deletions, in the app-private `nightly-recovery.db` database.

Samsung's documented SDK does not expose the daily sleeping HRV component. The optional **RingCoach 睡眠 HRV 读取** accessibility service reads the exact daily summary from Samsung Health's Energy Score screen. The owner enables it once in Android Accessibility settings. During a RingCoach-initiated refresh it may briefly display Samsung Health, read up to seven dated nights, then return to RingCoach. It does not run a screen workflow from the background job or unlock the phone. An unopened or locked phone therefore has background Energy Score polling, with HRV caught up when RingCoach next opens or progress is submitted. Catch-up is limited to seven nights per capture.

The service receives Samsung Health events only and performs no passive collection. Its polling loop exists only while a capture is pending. It verifies the date header, selects the active page rather than prefetched neighboring pages, waits for stable values, and distinguishes a verified no-data page from a navigation failure. Confirmed missing dates remove old screen readings for that date; a failed capture does not manufacture a replacement. A successful capture with today's HRV is reused for up to one hour. The current screen adapter supports the tested Simplified Chinese date labels and resource IDs. A Samsung Health layout or locale change can require an adapter update; incomplete captures remain labeled in stored sync status.

## Owner setup

1. Download the official SDK AAR as described in `app/libs/README.md` before building.
2. For development, enable Samsung Health Data SDK read mode in Samsung Health's About page, after reviewing Samsung's agreement. This mode is for development/testing; distribution requires Samsung partnership.
3. In RingCoach Settings, use **连接三星健康** and grant Energy Score read access.
4. Use **启用睡眠 HRV 自动读取** and enable the named service in Android Accessibility settings if you want exact daily HRV. It can be disabled there at any time.
5. Keep the existing Health Connect sleep and heart-rate read grants. These supply sleep sessions, stages, and heart-rate summaries independently of the two recovery summaries.

The owner completed the SDK agreement, read grant, and accessibility setup on the Fold7 during November 11 testing. No desktop connection or diagnostic export is needed for the implemented daily workflow.

## Submitting food or training

The journal's **提交更新** saves one dated text update and its photo attachments atomically. Nutrition recognition analyzes the day's text and submitted photos together; workout recognition extracts actual performance from the dated updates. A persistent automatic coach update waits for successful recognition and refreshes the nightly context. The saved Qwen credential is then used for a coaching request containing saved plans, progress, nutrition totals, Health Connect summary and dated recovery records. No model request is made just for periodic Energy Score polling. Opening the app to refresh health data does not by itself generate advice.

Successful advice and its exact input snapshot are archived using the coaching update ID; completing an older request cannot acknowledge a newer submission. Failures retain saved progress and the pending update, with **重试更新** in Journal. Pending updates survive app restart. Home displays latest advice and recovery values, Training displays the generated plan, and History groups saved journals and advice by date. The separate manual Coach tab has been removed. See the README for the four-tab flow.

## Interpretation and verified limitations

- Vendor daily sleeping HRV is kept separate from raw RMSSD/SDNN windows and Health Connect RMSSD. It is not substituted with the mean of diagnostic windows, and the coach does not derive an HRV z-score from it.
- The tested Fold7 returned Energy Score records with a null timezone offset and UTC-midnight calendar dates. The reader uses the UTC calendar date when that offset is absent, and the supplied offset when present. This mapping was checked against three consecutive days on the screen; the date basis remains in each record's metadata.
- The API retains a floating-point score while Samsung Health displays an integer. Both are recorded with their source and precision, without treating them as independent measurements or a readiness change.
- Samsung Health provenance alone does not establish Ring hardware attribution. Records retain that attribution as unverified rather than assuming every Samsung record comes from the Ring.
- Direct secondary Bluetooth discovery succeeded on this Ring. The short subscription probe received no notifications and did not establish automatic HRV history retrieval. The working HRV integration uses Samsung Health's screen, not a decoded Bluetooth health-history protocol.

## Validation

The debug build and JVM checks passed. Isolated on-phone storage tests verify revision, deduplication, missing-day clearing, persistence, and date filtering. A live test read the owner's existing progress, refreshed the SDK/Health Connect context, generated and saved a real coach card, and verified fresh daily HRV and Energy Score in its archived input and acknowledgment of the pending update. It did not add or edit a meal or workout. A forced scheduler execution also refreshed Energy Score with RingCoach in the background and the launcher foreground.

The source includes opt-in `NightlyAccessResearchTest` and `NightlyCoachingLiveTest`. They require explicit instrumentation arguments; normal test runs do not connect to the Ring or make a model request. The live coaching check saves a real card and requires the owner's saved Qwen credential and an already verified current recovery capture.

Sources: [Samsung SDK data types](https://developer.samsung.com/health/data/guide/features/data-types.html), [EnergyScoreType](https://developer.samsung.com/health/data/api-reference/-shd/com.samsung.android.sdk.health.data.request/-data-type/-energy-score-type/index.html), [SDK process and partner registration](https://developer.samsung.com/health/data/process.html), [Android accessibility services](https://developer.android.com/guide/topics/ui/accessibility/views/service).
