# RingCoach

Native Android prototype for checking Health Connect coverage on the Galaxy Z Fold7, exporting a reviewed telemetry sample, and analyzing it on Windows.

## Daily journal

The Chinese app has five tabs.

- **今日** opens today's saved journal or starts a new entry.
- **训练** displays a read-only progress table grouped by training day. Daily training input updates the latest completed performance automatically.
- **日志** automatically uses today's date and provides separate **训练**, **饮食**, and **其他记录** fields. Home refreshes the current date when the app resumes.
- **历史** lists saved days in descending date order. Tap **查看与编辑** to reopen one.
- **教练** prepares a reviewed context and generates a saved Qwen coaching card.

The **数据** button on Home opens Health Connect permissions, coverage, preview, and JSON export.

Journal entries save offline in the app-private SQLite database `ringcoach.db`, with one entry per date. Repeated saves update that day's entry. Existing days can be reopened from history, with a warning before replacing an unsaved draft. Draft fields survive screen navigation and activity recreation. Entries from the earlier layout migrate into **其他记录**, preserving the original notes, readiness, and discomfort text. The journal stores the text you enter for later use by the coaching feature.

## Routine, diet, and coaching settings

From **今日**, tap **设置**. Enter a reusable **基础训练计划**, **基础饮食计划**, and **教练指令**, then tap **保存设置**. The initial coaching instructions come from [prompts/coaching-policy.md](prompts/coaching-policy.md), packaged with the app. You can edit the default RPE cap and other instructions in the field.

Settings save offline in `ringcoach.db` and persist when the app restarts. Unsaved fields survive navigation and activity recreation. Opening and saving these fields makes no model request. Coach cards use these saved plans and instructions with the reviewed daily journal and available telemetry.

Use **导入设置 JSON** to load a local settings file, review the replacement, and save it. Schema version 1 contains `routine`, `diet`, and `instructions` strings. Version 2 adds `snapshot_date`, `nutrition_history`, and `exercise_baselines`. Imports are limited to 128 KiB. Settings and reference records save in a single transaction. Repeating an import updates the same reference keys.

**检查教练上下文** reads saved settings, seven calendar days of journal and nutrition history, and the latest applicable exercise-baseline snapshot from SQLite. The preview identifies imported nutrition as user-provided estimates and exercise baselines as references. It reports health telemetry as not loaded until the coaching request adds it. Coaching output can summarize the retrieved context without repeating fixed tracking tables.

Personal adaptations belong in the ignored `private/` folder or another local location. Only generic prompt templates are packaged in the APK.

The database upgrade supports earlier schemas through version 6. On-device tests verify that settings edits preserve journal entries, stored custom prompts and coach cards survive upgrades, seven-day context queries respect dates, and failed imports roll back all changes.

The debug build and Android unit tests passed, along with 14 on-device database and credential tests. The Settings screen, local import flow, and read-only Training tab were verified on the Fold7's outer display.

Database instrumentation tests use separate temporary databases. Build with `./gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest`, then install both APKs with `adb install -r` (add `-t` for the test APK) and run the following.

```powershell
adb shell am instrument -w -e class com.bcardo.ringcoach.JournalStoreTest com.bcardo.ringcoach.test/androidx.test.runner.AndroidJUnitRunner
```

Use the same debug signing key for app updates. The first attempt to run this task through Gradle's connected-device test runner failed on a signing-key mismatch, and its cleanup removed the debug app. The app was restored with the original user debug key. Three database tests passed through direct instrumentation, including migration from the earlier journal layout. The owner restored Health Connect read access, and the Data screen returned coverage again. Home, the three-field journal, empty history, and Data navigation were visually checked on the Fold7's outer display.

## Automatic training progress

Exercises retain the source plan's order with a stable number within each training day, stored in SQLite and included in model context. For example, a daily entry can refer to `Day 1, exercise 2`. A number without an identifiable training day requires clarification. Upgrading an existing import recovers its original insertion order without reimporting personal data.

Enter the completed workout in the daily **训练** field and save. The original text saves to SQLite first. With a saved Qianwen key, the app automatically sends the training text and known exercise names to Qwen3.8-Flash for extraction. It stores the recognized exercise, exact performance text, source evidence, and completion status in SQLite. The read-only **训练** tab displays each exercise's latest completed or partially completed performance and date. Exercises without a recognized completion show the imported reference, clearly labeled with its date.

Future plans do not replace completed performance. Units, per-side loads, assistance, and rep notation stay as entered. Ambiguous passages remain visible for clarification in the journal. Editing training invalidates its old extraction; a late reply cannot replace a newer revision. Diet-only edits preserve recognized training. Pending work resumes when the app opens or changes tabs. Failed requests preserve the original text and provide a retry button; extraction requires a network connection and does not run as a background service while the app is closed.

An opt-in live phone test used synthetic training to verify recognition, exclusion of tomorrow's planned weight, and persistence in a disposable database. No synthetic workout was added to the owner's journal.

## In-app coach cards

Save the Qianwen key under **设置**. Android Keystore-backed encryption protects it; the key is excluded from SQLite and settings imports. In **教练**, prepare and review the context before sending it. The request includes saved preferences, seven days of journal/nutrition history, reference baselines, latest recognized workout records, extraction statuses, and a fresh Health Connect summary. Missing HRV remains missing.

Successful Chinese cards have training, diet, and evidence/data-gap sections. SQLite archives the response and reviewed input snapshot, and the latest card reloads after reopening the app. A live Qwen card was generated and its persistence verified on the Fold7.

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
2. In RingCoach, tap **检查数据** (Check data) before granting access to verify that supported metrics show **未授权读取** (Read access not granted).
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
