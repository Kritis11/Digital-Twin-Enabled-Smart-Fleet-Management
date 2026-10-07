# One-command development setup for Windows (PowerShell 5.1 or later). Safe to run again.
#
#   .\setup.ps1             check prerequisites, create .env, install dependencies, start the
#                           infrastructure, create the schema and load 90 days of simulated history
#   .\setup.ps1 -NoData     everything except the simulated history
#
# If scripts are blocked:  powershell -ExecutionPolicy Bypass -File .\setup.ps1
# Afterwards, start the four programs as the README describes ("Start and stop"); on Windows the
# virtualenv programs are under .venv\Scripts instead of .venv/bin.
param([switch]$NoData)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

function Say($text)  { Write-Host "`n$text" -ForegroundColor Cyan }
function Fail($text) { Write-Host "ERROR: $text" -ForegroundColor Red; exit 1 }
function Run { & $args[0] $args[1..($args.Length - 1)]; if ($LASTEXITCODE -ne 0) { Fail "'$($args -join ' ')' failed" } }

Say '1/6 Checking prerequisites'
$python = if (Get-Command py -ErrorAction SilentlyContinue) { @('py', '-3.11') } else { @('python') }
$needed = [ordered]@{
    docker = 'Docker Desktop (https://docs.docker.com/get-docker/)'
    java   = 'Java 21 (JDK)'
    mvn    = 'Maven 3.9'
    node   = 'Node.js 22 or later, with npm'
    npm    = 'npm (comes with Node.js)'
}
$missing = $needed.Keys | Where-Object { -not (Get-Command $_ -ErrorAction SilentlyContinue) } | ForEach-Object { "  - $($needed[$_])" }
if ($missing) { Fail "missing:`n$($missing -join "`n")" }
& $python[0] $python[1..9] -c 'import sys; sys.exit(0 if sys.version_info[:2] == (3, 11) else 1)' 2>$null
if ($LASTEXITCODE -ne 0) { Fail 'Python 3.11 is needed (py -3.11, or python on the PATH)' }
docker info *> $null
if ($LASTEXITCODE -ne 0) { Fail 'Docker is installed but not running. Start Docker Desktop and run this again.' }
$javaMajor = [int]((cmd /c 'java -version 2>&1' | Select-String 'version "(\d+)').Matches[0].Groups[1].Value)
if ($javaMajor -lt 21) { Fail "Java 21 or later is needed; found version $javaMajor" }
$nodeMajor = [int](node -p 'process.versions.node.split(".")[0]')
if ($nodeMajor -lt 22) { Fail "Node.js 22 or later is needed; found $nodeMajor" }
Write-Host "ok: docker, java $javaMajor, maven, node $nodeMajor, python 3.11"

Say '2/6 Creating .env'
if (Test-Path .env) {
    Write-Host '.env already exists, leaving it alone'
} else {
    # every change-me value in the example becomes its own random secret
    $code = 'import pathlib, re, secrets; t = pathlib.Path(".env.example").read_text(); ' +
            'pathlib.Path(".env").write_text(re.sub(r"change-me[\w-]*", lambda _: secrets.token_urlsafe(32), t))'
    Run $python[0] $python[1..9] -c $code
    Write-Host 'created .env with generated passwords; the dashboard login is ADMIN_USERNAME / ADMIN_PASSWORD in it'
}
$envValues = @{}
Get-Content .env | Where-Object { $_ -match '^([A-Z_]+)=(.*)$' } | ForEach-Object { $envValues[$Matches[1]] = $Matches[2] }

Say '3/6 Installing dependencies (a few minutes the first time)'
foreach ($dir in 'ml-service', 'simulator') {
    if (-not (Test-Path "$dir\.venv")) { Run $python[0] $python[1..9] -m venv "$dir\.venv" }
}
Run ml-service\.venv\Scripts\pip install -q -r ml-service\requirements-dev.txt
Run simulator\.venv\Scripts\pip install -q -r simulator\requirements.txt
Push-Location frontend; Run npm ci --silent; Pop-Location
Push-Location backend;  Run mvn -q -DskipTests package; Pop-Location
Write-Host 'ok'

Say '4/6 Starting the infrastructure (TimescaleDB, Mosquitto, MinIO, Redis, OSRM)'
Run docker compose up -d --wait timescaledb mosquitto minio redis
Run docker compose up -d osrm   # prepares its map in the background; route planning uses straight lines until it is ready
Write-Host 'ok'

Say '5/6 Creating the database schema and the admin user'
$port = if ($envValues['SERVER_PORT']) { $envValues['SERVER_PORT'] } else { '8080' }
function BackendUp { try { (Invoke-WebRequest "http://localhost:$port/actuator/health" -UseBasicParsing -TimeoutSec 3).StatusCode -eq 200 } catch { $false } }
$backend = $null
if (BackendUp) {
    Write-Host "a backend is already running on port $port, using it"
} else {
    $jar = (Get-ChildItem backend\target\backend-*.jar | Select-Object -First 1).FullName
    $backend = Start-Process java -ArgumentList '-jar', "`"$jar`"" -WorkingDirectory backend -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput setup-backend.log -RedirectStandardError setup-backend.err.log
    foreach ($i in 1..60) {
        if (BackendUp) { break }
        if ($backend.HasExited) { Fail 'the backend stopped; see setup-backend.log' }
        Start-Sleep 2
    }
    if (-not (BackendUp)) { Stop-Process $backend; Fail 'the backend did not start; see setup-backend.log' }
}
Write-Host 'ok'

try {
    if ($NoData) {
        Say '6/6 Skipping simulated history (-NoData)'
    } else {
        Say '6/6 Loading 90 days of simulated history and training the remaining-life models'
        $rows = [int](docker compose exec -T timescaledb sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "select count(*) from telemetry"')
        if ($rows -gt 0) {
            Write-Host "the database already has $rows telemetry rows, not adding history"
        } else {
            Run simulator\.venv\Scripts\python simulator\simulator.py --fast-forward 90 --seed 42
            $login = @{ username = $envValues['ADMIN_USERNAME']; password = $envValues['ADMIN_PASSWORD'] } | ConvertTo-Json
            $token = (Invoke-RestMethod -Method Post "http://localhost:$port/api/auth/login" -ContentType 'application/json' -Body $login).accessToken
            Invoke-RestMethod -Method Post "http://localhost:$port/api/admin/reanalyse" -Headers @{ Authorization = "Bearer $token" } | Out-Null
            Push-Location ml-service; Run .venv\Scripts\python -m training.train_rul --no-report; Pop-Location
        }
    }
} finally {
    if ($backend) { Stop-Process $backend -ErrorAction SilentlyContinue }
}

Say 'Done. Start each of these in its own terminal:'
Write-Host @'
  cd backend;    mvn spring-boot:run
  cd ml-service; .venv\Scripts\uvicorn app.main:app --port 8000
  cd simulator;  .venv\Scripts\python simulator.py
  cd frontend;   npm start          # then open http://localhost:4200

Sign in with ADMIN_USERNAME / ADMIN_PASSWORD from .env.
The anomaly model needs about 45 minutes of live simulator data before it can be trained:
see "Retraining the models" in the README. Everything else works straight away.
'@
