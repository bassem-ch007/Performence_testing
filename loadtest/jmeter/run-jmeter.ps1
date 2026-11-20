<#
.SYNOPSIS
  Runs the JMeter load test (non-GUI) against the architecture whose compose profile is running.

.EXAMPLE
  # 1. start the monolith:   docker compose --profile monolith up -d --wait
  .\loadtest\jmeter\run-jmeter.ps1 -Architecture monolith
  # 2. switch:               docker compose --profile "*" rm -f -s ecommerce-mono ; docker compose up -d --wait
  .\loadtest\jmeter\run-jmeter.ps1 -Architecture microservices

.NOTES
  Results: loadtest\jmeter\results\<arch>-<timestamp>\ (results.jtl, HTML report, run-info.txt with the Grafana link)
#>
param(
    [Parameter(Mandatory = $true)][ValidateSet("monolith", "microservices")][string]$Architecture,
    [int]$Threads = 50,          # concurrent users (one seeded user per thread)
    [int]$RampUp = 60,           # seconds to start all threads
    [int]$Duration = 300,        # seconds of load (ramp-up included)
    [int]$ThinkMs = 500,         # pause at the end of each shopping iteration
    [string]$JMeterHome = $(if ($env:JMETER_HOME) { $env:JMETER_HOME } else { "$env:USERPROFILE\Desktop\apache-jmeter-5.6.3" })
)
$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$jmeter = Join-Path $JMeterHome "bin\jmeter.bat"
if (-not (Test-Path $jmeter)) { throw "jmeter.bat not found in $JMeterHome (use -JMeterHome or set JMETER_HOME)" }

# --- the matching stack must be up
$ports = @{ monolith = @(8080); microservices = @(8081, 8082, 8083) }[$Architecture]
foreach ($p in $ports) {
    try { Invoke-RestMethod "http://localhost:$p/actuator/health" -TimeoutSec 5 | Out-Null }
    catch { throw "Nothing healthy on port $p. Start the '$Architecture' profile first." }
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = Join-Path $here "results\$Architecture-$stamp"
New-Item -ItemType Directory -Force $out | Out-Null

$start = [DateTimeOffset]::UtcNow
# JVM warnings go to stderr: do not let PowerShell turn them into terminating errors
$ErrorActionPreference = "Continue"
& $jmeter -n `
    -t (Join-Path $here "ecommerce-load-test.jmx") `
    -q (Join-Path $here "$Architecture.properties") `
    "-Jthreads=$Threads" "-Jrampup=$RampUp" "-Jduration=$Duration" "-Jthink_ms=$ThinkMs" `
    -l (Join-Path $out "results.jtl") `
    -j (Join-Path $out "jmeter.log") `
    -e -o (Join-Path $out "report")
$exit = $LASTEXITCODE
$end = [DateTimeOffset]::UtcNow

$from = $start.AddSeconds(-30).ToUnixTimeMilliseconds()
$to = $end.AddSeconds(30).ToUnixTimeMilliseconds()
$grafana = "http://localhost:3000/d/$Architecture`?from=$from&to=$to"
@"
architecture : $Architecture
threads      : $Threads   ramp-up: $RampUp s   duration: $Duration s   think time: $ThinkMs ms
start (UTC)  : $($start.ToString("o"))
end (UTC)    : $($end.ToString("o"))
grafana      : $grafana
html report  : $(Join-Path $out "report\index.html")
"@ | Tee-Object -FilePath (Join-Path $out "run-info.txt")

if ($exit -ne 0) { Write-Warning "JMeter exited with code $exit (see $out\jmeter.log)" }
