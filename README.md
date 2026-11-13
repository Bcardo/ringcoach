# RingCoach

Saved progress now uses local nutrient sums after food/source/link validation. Switching apps defers foreground-only health reads and resumes them when RingCoach returns, preserving the existing submission. See [progress recovery and retry behavior](docs/progress-recovery.md).

New advice includes an expandable data-coverage summary, with current-night sleep intervals and transient HR samples, prior 30-day archived recovery comparisons and explicit freshness/omission counts. Missing current-day HRV and disabled screen reading are visible. Saved advice retains sleep intervals and HR statistics, not HR sample streams. See [coaching evidence, request limits and retention](docs/coaching-request-context.md).

Journal now extracts reported supplement events with dose/time unknowns, planned/taken/skipped/retracted status and source-linked corrections. Caloric supplements link to food facts so nutrition counts once. Each new submission enters a durable coaching queue and gets fresh feedback after recognition, including after restart and for historical dates. See [supplement intake and feedback behavior](docs/supplement-feedback.md) for retention, the seven-day model window and the saved-Settings standing-plan boundary.

See the [recovery verification and data-flow audit](docs/data-flow-audit.md) for what is verified, what stays local, what reaches Qwen, and the remaining Bluetooth/reliability gaps.

Native Android prototype for checking Health Connect coverage on the Galaxy Z Fold7, exporting a reviewed telemetry sample, and analyzing it on Windows.

Nightly recovery is now integrated with progress submissions: Energy Score refreshes through Samsung Health's SDK, and an explicitly enabled Samsung Health screen reader captures exact daily sleeping HRV. The app catches up recent nights when opened or when food/training is submitted, then automatically saves updated coaching advice after recognition finishes. See [nightly recovery setup, behavior, and validation](docs/nightly-recovery.md), including the distinction between background Energy Score polling and foreground HRV capture. The build requires the owner-downloaded Samsung SDK AAR described in [app/libs/README.md](app/libs/README.md).

## Set up nightly recovery on the phone

The Fold7 owner completed the Samsung SDK read-mode agreement, Energy Score read grant, and HRV accessibility setup on November 11, 2026. These permissions do not need to be granted for each meal or workout. Battery exemptions were not configured as part of that validation; the recommendations below are separate.

### How refresh works

| Trigger | What RingCoach does |
| --- | --- |
| In the background | An Android job requests Energy Score about every six hours, with a one-hour scheduling flex. Android may delay execution during idle/power-saving conditions. This job does not open Samsung Health, capture HRV, or call Qwen. |
| Open RingCoach | Refreshes Energy Score and, if the accessibility reader is enabled, catches up the last seven calendar days of daily HRV/score summaries. Returning to an already-open app also refreshes after five minutes away or when the date changes. |
| Submit progress with **提交更新** | Saves the inputs, waits for unified nutrition and workout recognition, refreshes recovery and Health Connect context, then automatically generates and saves a coach card with the saved Qwen key. |
| Settings → **刷新恢复数据** | Requests a health-data refresh without generating a coach card. The same HRV cache policy applies. |

HRV capture needs the phone unlocked. Samsung Health briefly appears while RingCoach checks the date and reads each day's summary, then RingCoach returns to the foreground. A capture has an approximately 25-second timeout. Leave the screen unlocked and allow this navigation to finish. A successful capture containing today's HRV is reused for up to one hour, including when the refresh button is pressed. If today's HRV was missing, the reader can try again on the next refresh.

Energy Score can refresh while RingCoach is closed. HRV catch-up happens when RingCoach is opened or progress is submitted; the app does not unlock the phone or run the HRV screen workflow unattended while it is locked. Open RingCoach at least once within each seven-day window to collect the nights available to this reader. The app does not need to stay open overnight.

### One-time required setup

Menu names can vary with One UI/Samsung Health versions; use the phone's Settings search if a path differs.

1. **Keep the normal Ring connection working.** Pair the Ring through Galaxy Wearable, keep Bluetooth enabled, and allow the usual Galaxy Wearable/Galaxy Ring Manager device permissions. Wear a sufficiently charged Ring during sleep and let it sync with the phone normally. RingCoach reads the resulting Samsung Health data; it does not replace the existing Ring pairing. If Samsung Health itself has no new sleep session or Energy Score, reconnect/sync there first.
2. **Enable Samsung Health SDK read mode for this development build.** Open Samsung Health → Settings → About Samsung Health. If needed, tap the version line quickly at least ten times to reveal the developer buttons. Choose **Developer mode (Samsung Health Data SDK)**, review Samsung's agreement, and enable developer mode/data reading. Choose the Data SDK button, not the older “Samsung Health SDK for Android” button. No write-access code is needed for Energy Score reading. This is development/testing mode; a distributed release requires Samsung partner registration. [Samsung's developer-mode instructions](https://developer.samsung.com/health/data/guide/developer-mode.html)
3. **Grant RingCoach Energy Score read access.** In RingCoach → Settings → **账号与健康连接**, tap **连接三星健康**, enable **能量得分** in Samsung's permission dialog, and finish. This integration requests read access only. If permission is later revoked, reconnect through this button.
4. **Enable exact sleeping HRV capture.** In RingCoach Settings → **账号与健康连接**, tap **启用睡眠 HRV 自动读取**, or open Android Settings → Accessibility → Installed apps. Enable **RingCoach 睡眠 HRV 读取** after reviewing its permission description. This is the one-time permission that lets the app read Samsung Health's daily HRV screen. The reader handles Samsung Health screens only during a RingCoach-initiated capture. Keep Samsung Health in the tested Simplified Chinese layout for the current adapter; a different locale or changed Samsung layout may require an adapter update.
5. **Keep Health Connect sleep and heart-rate permissions enabled.** Allow Samsung Health to write Sleep and Heart rate to Health Connect. In Settings → **Health Connect · 连接与诊断**, use **授权读取** and allow Sleep and Heart rate reads. These supply sleep sessions/stages and heart-rate context. HRV and skin-temperature read grants are optional for the Health Connect probe; granting them does not make Samsung export those records or replace the exact-HRV screen reader.
6. **Save the Qwen key for automatic advice.** In RingCoach Settings → **账号与健康连接**, save the Qianwen key and your routine, diet, and coaching instructions. A network connection is needed for meal/workout recognition and coaching. Reading already-available recovery summaries does not itself make a Qwen request. Without a saved key, progress remains saved and automatic coaching waits for configuration.
7. **Open RingCoach and verify the result.** Keep the phone unlocked while the first capture runs. Home should show today's Energy Score and sleeping HRV, using the same date and HRV value as Samsung Health. On the validated test day it showed score 82 and HRV 62 ms; those are historical test values, not expected values for every day.

Samsung Health's [SDK data-type list](https://developer.samsung.com/health/data/guide/features/data-types.html) includes Energy Score but does not expose the daily sleeping HRV component. That is why the two read paths need separate permissions. Neither USB debugging, a PC connection, a personal-data export, nor a Bluetooth HCI snoop log is required for this implemented daily workflow. HCI logging used during research can be turned off.

### Battery settings: recommended for reliability

Keep the phone's general battery optimization/Adaptive Battery settings enabled. Use app-specific exceptions for the apps whose background work matters.

1. Open **Android Settings → Apps → RingCoach → Battery → Unrestricted**. This is recommended for the background Energy Score job and service reliability. It is not a prerequisite for reading while RingCoach is in the foreground.
2. Open **Settings → Battery → Background usage limits** (on some versions, under Battery and device care → Battery). Remove RingCoach from **Sleeping apps** and **Deep sleeping apps** if listed. Add it to **Never sleeping apps / Never auto sleeping apps** if that option is available. Some versions do not offer apps already marked Unrestricted in this list; use the controls the phone provides.
3. Keep **Samsung Health**, **Galaxy Wearable**, and **Galaxy Ring Manager** out of **Deep sleeping apps** so the source data can sync normally. If Ring-to-Samsung-Health sync is delayed, consider adding the affected app to Never auto sleeping apps or setting its battery mode to Unrestricted as well. There is no need to change unrelated apps.
4. Avoid **Force stop** for these apps when relying on automatic refresh. After force-stopping RingCoach, reopen it. After a phone restart, unlock the phone and open RingCoach once to confirm the connection and HRV service are available.
5. Power saving and Android idle scheduling can still delay background work. Unrestricted mode does not guarantee an exact six-hour refresh time and does not enable locked-phone HRV capture. Opening RingCoach or submitting progress provides a foreground refresh opportunity.

These exceptions may increase background battery use; battery impact has not been measured. If you mainly use RingCoach when submitting progress and want to minimize background activity, foreground catch-up can still be used with normal optimization. The key requirement for HRV remains an enabled accessibility reader and an unlocked foreground capture.

References: [Samsung background/sleeping-app settings](https://www.samsung.com/ca/support/mobile-devices/galaxy-phone-sleeping-apps/), [Samsung battery optimization](https://www.samsung.com/us/support/galaxy-battery/optimization/), [Android job power-management limits](https://developer.android.com/topic/performance/power/power-details).

### Normal daily use and troubleshooting

1. Wear the Ring overnight and let Samsung Health sync normally.
2. When logging food or training, unlock the phone, open RingCoach, enter the progress, and tap **提交更新**. You do not need to type the score or HRV, export a file, or manually request a separate coach card.
3. Allow any Samsung Health capture navigation to finish, then keep RingCoach open until recognition and the coach update finish. The meal/workout analysis and coaching request are foreground app work; saving the journal does not guarantee the network requests will finish if the app is closed immediately. Saved inputs and a pending coach update survive a later restart.
4. Read the latest advice on Home or reopen the archived advice under its date in History. For a failed update, use **日志 → 重试更新**. Re-entering the original meal or workout is unnecessary.

| Symptom | Check/action |
| --- | --- |
| Samsung Health also lacks today's sleep/score/HRV | Check Ring battery, Bluetooth, Galaxy Wearable connection, and source sync. RingCoach cannot fetch a summary Samsung Health has not produced yet. |
| Energy Score works but HRV is missing | Verify **RingCoach 睡眠 HRV 读取** is still enabled, unlock the phone, and use RingCoach Settings → **刷新恢复数据**. Let Samsung Health navigation finish. Check the tested language/layout if the problem followed a Samsung Health update. |
| HRV looks unchanged after a same-day correction | A successful current-day capture can be reused for one hour. The current refresh button does not bypass that cache; retry after expiry. |
| Score is missing even though Samsung Health shows it | Recheck Data SDK developer read mode and the Energy Score grant through **连接三星健康**. These may need attention after reinstalling or resetting Samsung Health. |
| Background score refresh is late | Check app battery restrictions, sleeping/deep-sleeping lists, Power saving, and whether the app was force-stopped. The schedule is inexact; foreground refresh is available. |
| Advice did not update | Check the saved Qwen key, network connection, and recognition status. Reopen RingCoach and use **日志 → 重试更新** if shown. The latest saved advice remains available. |
| App was unused for more than seven days | The reader catches up only its current seven-day window. Older missed HRV is not backfilled by this implementation. |

Update the existing RingCoach installation with the same signing key to preserve local history. If you uninstall/reset the app or Samsung Health, repeat the relevant setup and permission steps. Turning off the HRV accessibility service stops automatic screen capture; SDK Energy Score reads can continue if their permission remains granted.

## Four tabs, one purpose each

| Tab | Purpose | Available actions |
| --- | --- | --- |
| **今日** | Current intake, applicable daily intake guidance, recovery and latest coach recommendation | Display only; one shared Settings entry in the header |
| **训练** | Full saved routine and exercise plan with latest performance, plus dated coach adjustments | Read only; no food input or hidden plan table |
| **日志** | Submit a dated progress update | One text box, optional camera/gallery attachments, **提交更新**; contextual retry only if processing fails |
| **历史** | Review saved days and the advice generated for them | Expand a date, open its saved advice, load earlier advice |

The old Coach tab, Home redirects, Training food-entry button, separate text-meal creation, consumed checkbox, meal refresh/list/editor and manual text/photo overlap choices are removed from normal navigation. Settings is available from the header of every tab. **账号与健康连接** contains the key and Samsung permissions; **高级 · 导入与上下文检查** contains import and context preview. **Health Connect · 连接与诊断** contains Health Connect permission, coverage and export tools.

## Dated progress submissions

Open **日志**, confirm the date (defaults to today), type food, training, symptoms or corrections, optionally attach photos, and tap **提交更新**. The input clears after submission so the next entry is a new update. You can submit once in the evening or several times during the day. Date changes are disabled while photos are attached so images cannot silently move to another date. Future dates are rejected.

Examples: submit lunch with a photo and “米饭吃了一半”; later submit “又喝了一杯蛋白粉”; later correct “午餐米饭实际只吃了四分之一”. There is no separate Add Meal step. Text describing a photo belongs to the same update. Multiple photo attachments can describe the same meal without implying multiple meals.

Each update is saved atomically with a stable submission ID, its date, timestamp and attachment IDs. Replaying the same ID does not append another event. Schema 11 preserves journals, plans, estimates and coach cards, and adds an ordered nutrition-turn queue plus confirmed daily food state. Confirmed old photo estimates become an explicitly aggregate legacy food fact before the app-private image is released; it does not invent an old per-meal breakdown. Unprocessed/ambiguous inputs remain retryable.

Nutrition recognition now updates the day's converted state incrementally. It receives the prior food facts/estimates, dated text context and the new input, attaching only that pending turn's new photos. It returns stable food IDs, descriptions/portions, source submissions, nutrient estimates, assumptions/questions and the updated daily totals. Unchanged food facts must be copied exactly; declared corrections retain the old ID and add the new source. Totals are checked against those facts. Prompt rules distinguish additions, corrections, repeated descriptions, planned food and explicit daily totals. A later addition after a daily total is counted and makes the result “recorded so far” again. Unknown nutrients remain unknown. A structurally invalid reply gets one repair request; invented evidence or unrequested changes to old food facts are rejected.

The successful daily state, turn acknowledgment and release of its app-private photo bytes commit in one transaction. Failed or interrupted conversion keeps the pending input for retry, and multiple queued turns process in order. A coaching failure after conversion does not require the photo again. SHA-256/provenance metadata can identify a repeated image without retaining its bytes. Original gallery files are untouched. A later correction uses the saved facts; visual reinspection after release requires attaching the photo again. Home keeps the last confirmed intake visible while explicitly labeling a pending/failed update as not yet included.

Home also displays the food recognized and the model's estimation assumptions. Total food weight is combined with visible ingredients; grams of protein powder are not treated as grams of pure protein. Matching photo/weight descriptions are treated as a supplement to the same meal unless another serving is explicitly reported. Aggregate evidence can cite several original fragments on separate lines; every fragment is checked against saved input. Clarification questions may be written naturally. Journal failure messages distinguish nutrition recognition, workout recognition and coaching, with additional network/key/response errors where available.

Training recognition reads the dated updates and known exercise references, extracting actual performance rather than planned performance. Nutrition state and extraction revisions prevent a late response from overwriting newer facts. Coaching waits for recognition, refreshes recovery/Health Connect summaries and receives the updated food state plus relevant previous same-day advice. Previous advice is not evidence that suggested food/training occurred. Food conversion, workout extraction and coaching remain separate internal requests; “photo once” means later submissions reuse converted facts rather than re-uploading old images. Updating a previous date within the seven-day context refreshes today's advice. Older raw journals remain in History, but current coaching only retrieves seven calendar days.

Input and retry state are retained privately so failed network requests do not require re-entry. They are implementation details, not a user-managed meal/partial-state workflow. Processing uses the saved key and needs network access and an active app; foreground work can be interrupted by Android. Reopen the app to resume pending work; failed work offers **重试更新** in Journal. Stored nutrition-turn IDs and saved coach context also allow a missed coaching handoff to be detected after restart. Do not resubmit the same food to retry a failure. The model does not independently retain your history: RingCoach supplies the saved context on each request.

## Plans, prompt and current intake

Use the shared **设置** entry to edit **基础训练计划**, **基础饮食计划** and **教练指令**. Existing imported personal plans remain in place. Daily intake targets come from these saved instructions and explicit dated adjustments, not a separate fixed numeric target configuration. The Home diet guidance explains the applicable plan and remaining intake when the supplied plan and recorded amounts support it. No unknown nutrient target is invented.

Training always displays the full saved routine and exercise table, with current performance or clearly dated reference baselines. Generated advice appears as dated adjustments alongside that plan; it does not replace or hide the plan, overwrite the owner's long-term settings, or change completed records. Home and Training label older advice rather than presenting it as newly generated.

## Data sent and stored

New photos are compressed JPEGs up to 1280 pixels and 700,000 bytes each, without original EXIF metadata. App-private bytes are temporary until successful food conversion; failed/unprocessed inputs remain available for retry. Later turns send converted food facts and only new photos. A legacy day with no successful conversion may need its unprocessed inputs once. Coaching receives saved plans/instructions, seven days of dated journal/nutrition context, performance references and health summaries; it does not receive the photos or a raw Bluetooth/PPG stream. API keys remain Android Keystore-encrypted and excluded from SQLite exports and settings imports.

History groups journal text, recent nutrition and archived advice by date. Opening saved advice makes no model request. Current nutrition summaries cover seven days; raw journal and advice history are retained beyond that window. Technical context inspection and reviewed health JSON export are Settings tools.

### Daily recovery archive (first slice)

Energy Score and Samsung's daily sleeping HRV accumulate in private `nightly-recovery.db` (schema 2); refreshing the current seven-day source window does not remove older archived dates. Existing schema-1 readings migrate in place. No additional permission or manual archive action is needed.

Each observation retains its wake date, units, source record ID, metric definition, first observation and last verification times. Repeated identical reads update verification time; corrected values/metadata, confirmed source deletions and restorations leave an audit trail. An unreadable or failed capture preserves earlier readings. Only a complete successful source read can mark absent records missing; removed records remain in the revision audit rather than active history.

`NightlyRecoveryStore.history(start, end)` returns every date in an inclusive window of up to 366 days, with one canonical value per metric and all source observations. This is a query limit, not a retention limit. Energy Score prefers SDK precision and falls back to the screen; sleeping HRV uses Samsung's daily screen value. Conflicting preferred-source readings yield an explicit ambiguous/null result rather than an average. Missing dates distinguish `not_checked` from `confirmed_missing`; neither is zero. Per-source coverage and the latest sync attempt are separate from a reading's verification time. `revisions(date, metric)` exposes the correction trail.

The archive only contains locally observed data. It cannot recover HRV missed outside the reader's seven-day catch-up window. Sleep/HR summaries, backup and expanded historical coaching remain separate work. This archive stores no meal photos or detailed heart-rate traces.

### View recovery history on the phone

Open **RingCoach → 历史 → 恢复趋势**. Select **近 7 天** or **近 30 天** to see Energy Score and sleeping HRV by wake date, with units, the latest observed value/date and valid-day counts. Missing/uncollected/conflicting days remain gaps, not zeros. Lines stop at gaps and source/definition changes. The two charts stack on the cover display and sit side by side when enough width is available.

Tap a chart date or use **查看日期** (scroll below the charts on the cover display). The date detail shows source readings, precision, archive version and last verification time; that date's journal and all saved coaching advice appear first below it. Source-read failures show a warning while preserving archived values. Older readings are labeled with their actual date. Today’s unverified archived reading is explicitly marked. Range/date choices survive activity recreation, and the view reloads after a foreground recovery refresh completes.

This is an Android screen in the installed development build, not a web dashboard. Opening history, changing ranges and inspecting saved advice do not trigger an LLM request. No new navigation tab or meal-entry control is added. The coaching payload still uses its existing seven-day window.

## Validation

Build and run JVM tests with `./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest`. Install app/test APKs with the existing debug signing key and `adb install -r` (also `-t` for the test APK); never uninstall to update the owner's app.

`ProgressSubmissionTest` uses disposable databases to verify ordered additions/corrections, replay protection, stale-result rejection, photo/date rollback, unified totals without a second photo sum, and schema-9 migration preserving personal settings and records. `ProgressSubmissionLiveTest` is opt-in with `-e live_progress true`; it sends synthetic breakfast, a generated test drink label with matching text, and a correction to Qwen, checking totals after each update. No synthetic entry is saved into the owner's journal. These tests verify processing behavior, not the nutritional accuracy of arbitrary food photos.

The earlier four-tab build passed 33 JVM tests and 24 on-phone database/UI tests. The incremental path adds `DailyFoodStateTest` and `IncrementalNutritionStoreTest` for fact stability, corrections, totals, ordered retries, stale responses, transaction rollback, schema-10 migration, visible confirmed state and coaching-handoff recovery. `IncrementalNutritionLiveTest` (`-e live_incremental true`) reads a synthetic nutrition label from the image alone, then adds a text snack and corrects the meal; later requests contain no prior photo bytes and unchanged facts remain identical. It uses a disposable database. These checks establish state/request behavior, not general food-image nutritional accuracy. `ProgressRecoveryLiveTest` remains an explicit opt-in owner-data retry, not an ordinary synthetic test.

The incremental build passed 44 JVM tests and 32 on-phone storage/navigation tests, plus the opt-in three-turn live test. `IncrementalOwnerAuditTest` (`-e audit_incremental true`) separately verified schema 11, saved plans/history, preserved prior nutrition values and zero image bytes in released inputs on the owner installation; it reports counts rather than personal measurements.

## Build on Windows

1. Install JDK 17 or 21 and set `JAVA_HOME` to its installation directory.
2. In Android Studio's SDK Manager, install Android SDK Platform 36, Android SDK Build-Tools 35.0.0, and Android SDK Platform-Tools.
3. Set `ANDROID_HOME` to the SDK directory. Android Studio can also configure the SDK for the project in `local.properties`.
4. From the project root, run `./gradlew.bat :app:assembleDebug` in PowerShell. The APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Install and verify on the Fold7

If Developer options is hidden, go to **Settings → About phone → Software information** and tap **Build number** seven times. Return to Settings, open **Developer options**, and enable **USB debugging**. Connect the unlocked Fold7 over USB and accept the debugging prompt. [Samsung Developer options guide](https://developer.samsung.com/health/data/guide/phone-developer-options.html)

Run the following in PowerShell with `ANDROID_HOME` set to the SDK directory.

```powershell
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
& $adb devices
& $adb install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb shell am start -W -n com.bcardo.ringcoach/.MainActivity
```

Check that `adb devices` lists the Fold7 as `device`, the install reports `Success`, and RingCoach opens on the phone. If multiple devices are connected, pass `-s <serial>` to `adb` before the `install` and `shell` commands.

For a manual install, copy `app-debug.apk` to the phone, open it from **My Files**, and follow the installation prompt. [Samsung app installation help](https://www.samsung.com/us/support/troubleshoot/TSG10001914/)

## Check Health Connect coverage

1. Sync the Ring in Samsung Health. In Samsung Health settings, open **Health Connect** and allow Samsung Health to write the available metrics you want to check.
2. In RingCoach Settings → **Health Connect · 连接与诊断**, tap **检查数据** (Check data) before granting access to verify that supported metrics show **未授权读取** (Read access not granted).
3. Tap **授权读取** (Grant read access), grant just one available metric, then tap **检查数据** again. Check that the granted metric returns coverage or an empty result and the other supported metrics still show the permission status.
4. Grant the remaining metrics you want to inspect and run the check again. Review the counts, coverage dates, and source app. The probe includes records from all available sources and displays device metadata when present.

The probe checks a rolling seven-day window. It displays counts and coverage on the phone. Samsung's [DataViewer](https://developer.samsung.com/health/data/guide/data-viewer.html) is an optional way to check whether Samsung Health has additional metrics.

### Fold7 validation

On Android 17 with Samsung Health 7.1.3.001, the seven-day probe found sleep sessions with stages and timestamped heart-rate samples. Both came from Samsung Health and neither included device metadata sufficient to confirm Galaxy Ring attribution. Health Connect returned no RMSSD or skin-temperature records for the checked window with read access granted.

After heart-rate access was revoked, a new check reported heart rate as unauthorized and continued to return sleep coverage. The two granted record types with no data still showed an empty result.

On this Fold7, RingCoach had Health Connect HRV read permission, while Samsung Health requested write permission for heart rate and did not request write permission for HRV. Samsung Health uses overnight HRV in [Energy Score](https://www.samsung.com/ae/support/mobile-devices/how-to-check-energy-score-with-the-galaxy-ring/). The owner's [personal-data download](https://www.samsung.com/us/support/answer/ANS10001379/) contained positive sleeping HRV values in the Energy Score CSV, including dates in the Health Connect comparison window. A JSON file referenced by the dedicated HRV CSV contained `rmssd`, `sdnn`, `start_time`, and `end_time` fields. The field units and the relationship between those values and Energy Score sleeping HRV are unverified. The download can backfill historical HRV. Automatic Galaxy Ring HRV access remains unavailable through the documented Samsung Health Data SDK types and the tested Health Connect configuration.

## Preview and export a sample

1. Tap **检查数据** (Check data), then choose which metrics to include under **选择要导出的指标** (Choose export metrics).
2. Tap **预览 JSON** (Preview JSON) to review the exact file contents. The preview requires at least one readable record in the selection. Selected metrics with no records or access are represented by their status and an empty array.
3. Tap **保存 JSON** (Save JSON) and choose a local folder such as **Downloads**. The app writes the reviewed preview as UTF-8 JSON. Keep personal exports outside the repository.

Schema version 1 uses UTC timestamps with original offsets where available, explicit units in field names, and optional source metadata. The export includes records within the seven-day window and caps each preview at 1 MiB. For a shareable example and a schema check, use the [synthetic fixture](app/src/test/resources/synthetic-telemetry.json) and run `./gradlew.bat :app:testDebugUnitTest`.

The owner confirmed that a reviewed export saved successfully on the Fold7.

## Analyze a reviewed export on Windows

Install [Codex CLI](https://learn.chatgpt.com/docs/codex/cli) and Python 3.10 or newer. The analysis command uses an OpenAI Platform API key from the current shell. It runs Codex in a temporary directory with shell, web search, plugins, and subagents disabled. Only derived counts and statistics go to OpenAI. The export file, exact timestamps, and source-device details stay on the computer. [OpenAI Docs for non-interactive mode](https://learn.chatgpt.com/docs/non-interactive-mode)

From the project root, run the synthetic sample locally first. This prints the exact facts that would be submitted and needs no key.

```powershell
$fixture = '.\app\src\test\resources\synthetic-telemetry.json'
python .\scripts\analyze_telemetry.py $fixture --facts-only
```

For the live synthetic check, use an existing `CODEX_API_KEY` or `OPENAI_API_KEY` in the shell, or enter your key at a masked PowerShell prompt. The script defaults to `gpt-5.4-mini`. Use `--model` if your API project uses another model.

```powershell
$key = Read-Host 'OpenAI Platform API key' -AsSecureString
$env:CODEX_API_KEY = [pscredential]::new('RingCoach', $key).GetNetworkCredential().Password
python .\scripts\analyze_telemetry.py $fixture
```

Confirm that the report is in Simplified Chinese, the synthetic HRV is described as missing, and the skin-temperature value is described as a change. The first line records the CLI version and model. Then copy the reviewed Fold7 export to a local folder outside the repository and replace the example path below. Use `--facts-only` to review what the live command will submit.

```powershell
$export = 'C:\private\ringcoach-export.json'
python .\scripts\analyze_telemetry.py $export --facts-only
python .\scripts\analyze_telemetry.py $export
Remove-Item Env:CODEX_API_KEY
```

The desktop reader was checked locally against the saved Fold7 export. It accepted the sleep and heart-rate records and reported RMSSD and skin temperature as empty for that export window. The desktop analysis is tracked in [issue #4](https://github.com/Bcardo/ringcoach/issues/4). The investigation into getting fresh overnight HRV automatically is tracked in [issue #9](https://github.com/Bcardo/ringcoach/issues/9).

### Try Qwen3.8-Flash

In PowerShell, enter your [Qianwen AI Platform API key](https://platform.qianwenai.com/docs/api-reference/preparation/api-key) at the masked prompt and run the synthetic check. The script chooses the Qianwen endpoint for the key type and sends derived counts and statistics. It defaults to `qwen3.8-flash` for this provider.

```powershell
$ringcoachKey = Read-Host 'Qianwen API key' -AsSecureString
$env:DASHSCOPE_API_KEY = [pscredential]::new('RingCoach', $ringcoachKey).GetNetworkCredential().Password
python .\scripts\analyze_telemetry.py .\app\src\test\resources\synthetic-telemetry.json --provider qwen
```

After the synthetic check passes, pass a reviewed export in place of the fixture. Clear the session variable with `Remove-Item Env:DASHSCOPE_API_KEY` when finished.

The owner ran both the synthetic fixture and the reviewed Fold7 export successfully with `qwen3.8-flash`. The synthetic report matched the fixture's sample counts and heart rate, preserved the skin-temperature delta, and identified RMSSD as missing. The Fold7 report matched the locally computed sleep-session lengths, sleep-stage interval counts, and heart-rate statistics. It flagged RMSSD and skin temperature as missing from the export.

### Customize the prompt

Edit [prompts/telemetry-summary.txt](prompts/telemetry-summary.txt) to change the analysis instructions. Each run reads the file again. You can also use a personal UTF-8 file.

```powershell
python .\scripts\analyze_telemetry.py $export --provider qwen --prompt-file 'C:\private\my-ringcoach-prompt.txt'
```

The report uses `observations` and `limitations` string arrays, with 1–3 observations and 1–2 limitations. Keep those fields in your prompt. The [coaching policy](prompts/coaching-policy.md) contains editable defaults for the future coaching feature, including an RPE cap of 7.5. The [rule decisions and acceptance examples](docs/coaching-rules.md) describe how matching rules interact.
