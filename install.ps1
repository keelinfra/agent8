<#
.SYNOPSIS
Installs agent8 on a Windows build host with old Java (8, 11 or 17), and optionally swaps
it in for the agent.jar the host already uses. Same options as install.sh.

.EXAMPLE
powershell -ExecutionPolicy Bypass -File install.ps1 -Url https://jenkins.example.com/ -Replace C:\jenkins\agent.jar

.PARAMETER Url      Jenkins controller; agent8 is matched to its remoting version
.PARAMETER Version  remoting version to install instead of asking the controller
.PARAMETER Replace  swap the agent.jar at this path for agent8 (the original is kept as
                    <path>.before-agent8), so the service or start command stays unchanged
.PARAMETER Restore  undo -Replace: put back <path>.before-agent8 and remove jvmdg.jar
.PARAMETER Dir      where to install (default $env:ProgramData\agent8)
.PARAMETER From     offline: take the agent8 jar and jvmdg.jar from this folder
.PARAMETER Java     java.exe to use (default $env:JAVA_HOME\bin\java.exe, then java on PATH)
.PARAMETER Target   which build to install: 8, 11 or 17 (default: from the host's Java)
.PARAMETER Repo     release source (default https://github.com/keelinfra/agent8)
#>
param(
    [string]$Url, [string]$Version, [string]$Replace, [string]$Restore,
    [string]$Dir = (Join-Path $env:ProgramData 'agent8'),
    [string]$From, [string]$Java, [string]$Target,
    [string]$Repo = 'https://github.com/keelinfra/agent8'
)
# Windows PowerShell 5.1 compatible.
$ErrorActionPreference = 'Stop'
function Say($m) { Write-Host "agent8: $m" }
function Die($m) { Write-Host "agent8: $m" -ForegroundColor Red; exit 1 }

if ($Restore) {
    if (-not (Test-Path "$Restore.before-agent8")) { Die "no $Restore.before-agent8 to restore" }
    Move-Item -Force "$Restore.before-agent8" $Restore
    Remove-Item -Force -ErrorAction SilentlyContinue (Join-Path (Split-Path $Restore) 'jvmdg.jar')
    Say "restored the original $Restore; restart the agent as usual"
    exit 0
}

# Java
if (-not $Java) {
    if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) { $Java = "$env:JAVA_HOME\bin\java.exe" }
    else {
        $cmd = Get-Command java -ErrorAction SilentlyContinue
        if (-not $cmd) { Die 'no java found; pass -Java C:\path\to\java.exe' }
        $Java = $cmd.Source
    }
}
function RunJava([string[]]$javaArgs) {
    # java writes to stderr; collect both streams as plain text.
    $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    $out = & $Java @javaArgs 2>&1 | ForEach-Object { "$_" }
    $code = $LASTEXITCODE; $ErrorActionPreference = $old
    return @{ Out = @($out); Code = $code }
}
$jv = ''
foreach ($line in (RunJava @('-version')).Out) { if ($line -match 'version "([^"]+)"') { $jv = $Matches[1]; break } }
Say "using $Java ($jv)"
if ($jv -match '^1\.[0-7]') { Die "Java $jv is too old; agent8 needs Java 8 or newer" }
$major = if ($jv -match '^1\.') { 8 } elseif ($jv -match '^(\d+)') { [int]$Matches[1] } else { 8 }
$asset = if ($major -ge 17) { 'agent8-java17.jar' } elseif ($major -ge 11) { 'agent8-java11.jar' } else { 'agent8-java8.jar' }
if ($Target) {
    if ($Target -notin @('8', '11', '17')) { Die '-Target must be 8, 11 or 17' }
    $asset = "agent8-java$Target.jar"
}
$want = $asset

$work = Join-Path ([IO.Path]::GetTempPath()) ("agent8." + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $work | Out-Null
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    $ProgressPreference = 'SilentlyContinue'
    function Fetch($u, $f) {
        try { Invoke-WebRequest -UseBasicParsing -Uri $u -OutFile $f; return $true } catch { return $false }
    }
    function Release($tag) {
        $base = if ($tag -eq 'latest') { "$Repo/releases/latest/download" } else { "$Repo/releases/download/$tag" }
        foreach ($try in 1..3) {
            if (Fetch "$base/jvmdg.jar" "$work\jvmdg.jar") {
                $script:asset = $want
                if ($want -ne 'agent8-java8.jar' -and (Fetch "$base/$want" "$work\agent8.jar")) { return $true }
                $script:asset = 'agent8-java8.jar'
                if (Fetch "$base/agent8-java8.jar" "$work\agent8.jar") { return $true }
            }
            Start-Sleep -Seconds (5 * $try)
        }
        return $false
    }

    if ($From) {
        if (-not (Test-Path "$From\$asset") -and -not $Target) { $asset = 'agent8-java8.jar' }
        if (-not (Test-Path "$From\$asset") -or -not (Test-Path "$From\jvmdg.jar")) { Die "no $asset and jvmdg.jar in $From" }
        Copy-Item "$From\$asset" "$work\agent8.jar"; Copy-Item "$From\jvmdg.jar" $work
    } else {
        if (-not $Version -and $Url) {
            if (-not (Release 'latest')) { Die "cannot download from $Repo; download the release files elsewhere and use -From" }
            $agentUrl = $Url.TrimEnd('/') + '/jnlpJars/agent.jar'
            if (-not (Fetch $agentUrl "$work\controller-agent.jar")) { Die "cannot download $agentUrl" }
            $Version = ((RunJava @('-cp', "$work\agent8.jar", 'io.jenkins.agent8.Main', '--remoting-version', "$work\controller-agent.jar")).Out | Select-Object -Last 1).Trim()
            Say "controller uses remoting $Version"
        }
        $ok = $false
        if ($Version) { $ok = Release "remoting-$Version"; if (-not $ok) { Say "no agent8 build for remoting $Version yet; using the latest build" } }
        if (-not $ok -and -not (Release 'latest')) { Die "cannot download from $Repo; download the release files elsewhere and use -From" }
    }

    # Check that this Java runs it before touching anything.
    $r = RunJava @('-jar', "$work\agent8.jar", '-version')
    if ($r.Code -ne 0) { $r.Out | ForEach-Object { Write-Host $_ }; Die "$asset does not start with $Java (output above)" }
    Say "$asset runs remoting $(($r.Out | Select-Object -Last 1).Trim()) on this host"

    New-Item -ItemType Directory -Force $Dir | Out-Null
    Copy-Item -Force "$work\agent8.jar", "$work\jvmdg.jar" $Dir
    Say "installed $Dir\agent8.jar and $Dir\jvmdg.jar"

    if ($Replace) {
        $targetDir = Split-Path $Replace
        if (-not (Test-Path $targetDir)) { Die "$targetDir does not exist" }
        # Windows locks a jar a running JVM has open: stop the agent first.
        try {
            if ((Test-Path $Replace) -and -not (Test-Path "$Replace.before-agent8")) {
                Move-Item $Replace "$Replace.before-agent8"
                Say "kept the original jar as $Replace.before-agent8 (undo: -Restore $Replace)"
            }
            Copy-Item -Force "$work\agent8.jar" $Replace
            Copy-Item -Force "$work\jvmdg.jar" (Join-Path $targetDir 'jvmdg.jar')
        } catch {
            Die "cannot replace $Replace ($($_.Exception.Message)); stop the agent or its service first"
        }
        Say "replaced $Replace; start the agent or its service as usual"
    } else {
        Say 'start the agent as usual, with this jar:'
        Say "  `"$Java`" -jar `"$Dir\agent8.jar`" -url <controller> -secret <secret> -name <node> -workDir <dir>"
    }
} finally {
    Remove-Item -Recurse -Force -ErrorAction SilentlyContinue $work
}
