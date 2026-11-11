# RingCoach

Native Android prototype for checking Health Connect coverage on the Galaxy Z Fold7, exporting a reviewed telemetry sample, and analyzing it on Windows.

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
