param(
    [string]$Classes = 'com.bcardo.ringcoach.BackgroundProgressTest,com.bcardo.ringcoach.SupplementFeedbackStoreTest',
    [string]$Adb = (Join-Path $env:TEMP 'ringcoach-sdk\platform-tools\adb.exe')
)
$ErrorActionPreference = 'Stop'
$package = 'com.bcardo.ringcoach'
function ReaderConfigured {
    $services = & $Adb shell settings get secure enabled_accessibility_services
    return "$services" -match 'com\.bcardo\.ringcoach/(?:com\.bcardo\.ringcoach\.)?SamsungHealthScreenReader'
}
$configuredBefore = ReaderConfigured
# --no-restart requires an existing process. Never fall back to the default force-stopping runner.
& $Adb shell am start -W -n "$package/.MainActivity"
if ($LASTEXITCODE -ne 0) { throw 'Could not open RingCoach.' }
$processId = & $Adb shell pidof $package
if (-not $processId) { throw 'RingCoach has no running process; no tests were started.' }
$result = & $Adb shell am instrument --no-restart -w -r -e class $Classes "$package.test/androidx.test.runner.AndroidJUnitRunner"
$result | Write-Output
if ((ReaderConfigured) -ne $configuredBefore) { throw 'Accessibility enablement changed. Inspect the setting; do not silently toggle it.' }
if (($result -join "`n") -notmatch 'OK \(\d+ tests?\)' -or ($result -join "`n") -match 'FAILURES!!!|commandError=true') {
    throw 'Android checks did not pass. The force-stopping runner was not used.'
}
