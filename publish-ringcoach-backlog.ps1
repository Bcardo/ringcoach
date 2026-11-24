[CmdletBinding(SupportsShouldProcess)]
param()

# Historical initial-backlog publisher, retained under issue #78.
# Its source specification is superseded. Use -WhatIf for inspection; this script is
# not part of app deployment and must not be run to refresh the current backlog.

$ErrorActionPreference = 'Stop'
$owner = 'Bcardo'
$repo = "$owner/ringcoach"
$specPath = Join-Path $PSScriptRoot 'ringcoach_prd_and_engineering_specification.md'
$spec = Get-Content -LiteralPath $specPath -Encoding UTF8 -Raw
$rows = $spec -split '\r?\n' | ForEach-Object {
    if ($_ -match '^\| (RC-[\w-]+) \| (.*?) \| (.*?) \| (.*?) \|$') {
        [pscustomobject]@{ Id = $Matches[1]; Title = $Matches[2]; Acceptance = $Matches[3] }
    }
}
$byId = @{}
foreach ($row in $rows) { $byId.Add($row.Id, $row) }
$research = @{}
foreach ($section in [regex]::Matches($spec, '(?ms)^### \[(RC-[\w-]+)\] (.*?)\r?\n(.*?)(?=^#{1,3} |\z)')) {
    $research.Add($section.Groups[1].Value, "### $($section.Groups[2].Value)`n`n$($section.Groups[3].Value.Trim())")
}
$order = @('RC-01', 'RC-02', 'RC-03', 'RC-04', 'RC-MVP', 'RC-LATER')
foreach ($id in $order) {
    if (-not $byId.ContainsKey($id)) { throw "Missing issue definition: $id" }
}
if (-not $PSCmdlet.ShouldProcess($repo, "Create public repository if absent, publish missing issues, and attach research")) {
    foreach ($id in $order) {
        "[$id] $($byId[$id].Title)"
        if ($research.ContainsKey($id)) { $research[$id] -split '\r?\n' | Select-Object -First 1 }
    }
    return
}

function Invoke-GitHubCli {
    param([string[]]$Arguments)
    $result = & gh @Arguments
    if ($LASTEXITCODE -ne 0) { throw "GitHub CLI failed: gh $($Arguments[0]) $($Arguments[1])" }
    return $result
}

$login = Invoke-GitHubCli -Arguments @('api', 'user', '--jq', '.login')
if ($login -ne $owner) { throw "Expected GitHub account $owner; authenticated as $login." }

# Check repository existence before attempting creation. A name collision also fails safely.
$null = & gh repo view $repo --json nameWithOwner 2>$null
if ($LASTEXITCODE -ne 0) {
    $null = Invoke-GitHubCli -Arguments @('repo', 'create', $repo, '--public', '--add-readme', '--description', 'Personal Android prototype for validating Galaxy Ring data and analyzing it with Codex')
}
$metadata = Invoke-GitHubCli -Arguments @('repo', 'view', $repo, '--json', 'nameWithOwner,visibility,hasIssuesEnabled,url') | ConvertFrom-Json
if ($metadata.nameWithOwner -ne $repo -or $metadata.visibility -ne 'PUBLIC' -or -not $metadata.hasIssuesEnabled) {
    throw "Expected a public repository at $repo with Issues enabled. No issues were written."
}

$existing = Invoke-GitHubCli -Arguments @('issue', 'list', '--repo', $repo, '--state', 'all', '--limit', '250', '--json', 'title,number,url,body') | ConvertFrom-Json
$links = @{}
$issueNumbers = @{}
$outcomes = @{
    'RC-01' = 'As the owner, I want to install a minimal app on my Fold7 so I can test the Health Connect path on real hardware.'
    'RC-02' = 'As the owner, I want to see which Samsung Health records I can read so I can base the prototype on actual device data.'
    'RC-03' = 'As the owner, I want to review and export only the fields needed for analysis.'
    'RC-04' = 'As the owner, I want to analyze an exported sample with Codex and my own OpenAI API key.'
    'RC-MVP' = 'Prove the full path from actual Fold7 Health Connect records to a short Codex analysis. Publish only redacted validation results.'
    'RC-LATER' = 'Define the coaching behavior after the device prototype is validated. This is outside the MVP.'
}
$dependencies = @{ 'RC-02' = 'RC-01'; 'RC-03' = 'RC-02'; 'RC-04' = 'RC-03'; 'RC-LATER' = 'RC-MVP' }

foreach ($id in $order) {
    $issueTitle = "[$id] $($byId[$id].Title)"
    $matchesById = @($existing | Where-Object { $_.title.StartsWith("[$id] ") })
    if ($matchesById.Count -gt 1) { throw "Multiple issues have tracking ID $id. Resolve the duplicate before retrying." }
    $researchMarker = "<!-- ringcoach-research:$id -->"
    if ($matchesById.Count -eq 1) {
        $issue = $matchesById[0]
        $links[$id] = $issue.url
        $issueNumbers[$id] = $issue.number
        if ($research.ContainsKey($id) -and ([string]$issue.body).IndexOf($researchMarker) -lt 0) {
            $bodyFile = [System.IO.Path]::GetTempFileName()
            try {
                $updatedBody = "$($issue.body)`n`n$researchMarker`n$($research[$id])"
                [System.IO.File]::WriteAllText($bodyFile, $updatedBody, [System.Text.UTF8Encoding]::new($false))
                $null = Invoke-GitHubCli -Arguments @('issue', 'edit', [string]$issue.number, '--repo', $repo, '--body-file', $bodyFile)
            } finally {
                Remove-Item -LiteralPath $bodyFile
            }
        }
        continue
    }
    $body = @($outcomes[$id], '', 'Acceptance criteria:', '', "- [ ] $($byId[$id].Acceptance)")
    if ($dependencies.ContainsKey($id)) { $body += @('', "Depends on #$($issueNumbers[$dependencies[$id]]).") }
    if ($id -eq 'RC-MVP') {
        $body += @('', 'Prototype issues:', '')
        foreach ($childId in @('RC-01', 'RC-02', 'RC-03', 'RC-04')) {
            $body += "- [ ] #$($issueNumbers[$childId])"
        }
    }
    if ($research.ContainsKey($id)) { $body += @('', $researchMarker, $research[$id]) }
    $bodyFile = [System.IO.Path]::GetTempFileName()
    try {
        [System.IO.File]::WriteAllLines($bodyFile, [string[]]$body, [System.Text.UTF8Encoding]::new($false))
        $issueUrl = Invoke-GitHubCli -Arguments @('issue', 'create', '--repo', $repo, '--title', $issueTitle, '--body-file', $bodyFile)
        if ($issueUrl -notmatch '/issues/(\d+)$') { throw "Unexpected issue URL for $id. Check GitHub before retrying." }
        $links[$id] = $issueUrl
        $issueNumbers[$id] = $Matches[1]
    } finally {
        Remove-Item -LiteralPath $bodyFile
    }
}

$metadata.url
foreach ($id in $order) { "${id}: $($links[$id])" }
