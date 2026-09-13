# ================================================================
# Publish OTA update to the server package:
#   build APK -> copy to release_server/update -> write version.json
#
# Usage:
#   powershell -ExecutionPolicy Bypass -File release_update.ps1
#   powershell -ExecutionPolicy Bypass -File release_update.ps1 -Notes "1. fix xxx" -DeployServer
#
# Note:
#   Builds the Debug APK (same debug signature as the installed app, so it can
#   overwrite-install). Configure a release signing key to ship release builds.
# ================================================================
param(
    [string]$Notes = "",
    [switch]$DeployServer
)

$ErrorActionPreference = 'Continue'

$ROOT       = 'd:\aid\169'
$JAVA_HOME  = 'D:\android\jbr'
$APK_SRC    = Join-Path $ROOT 'app\build\outputs\apk\debug\app-debug.apk'
$UPDATE_DIR = Join-Path $ROOT 'release_server\update'
$APK_DST    = Join-Path $UPDATE_DIR 'app-release.apk'
$GRADLE_KTS = Join-Path $ROOT 'app\build.gradle.kts'

$env:JAVA_HOME = $JAVA_HOME
Set-Location $ROOT

function Fail($msg) {
    Write-Host ""
    Write-Host ("[FAIL] " + $msg) -ForegroundColor Red
    exit 1
}

# ---------- 0. read versionCode / versionName ----------
$gradleText = Get-Content -Path $GRADLE_KTS -Raw -Encoding UTF8
$versionCode = 0
$versionName = ""
if ($gradleText -match 'versionCode\s*=\s*(\d+)')      { $versionCode = [int]$Matches[1] }
if ($gradleText -match 'versionName\s*=\s*"([^"]+)"')  { $versionName = $Matches[1] }
if ($versionCode -le 0) { Fail "cannot read versionCode from app/build.gradle.kts" }
Write-Host ("Release version: " + $versionName + " (versionCode=" + $versionCode + ")") -ForegroundColor Cyan

# ---------- 1. build debug APK ----------
Write-Host ""
Write-Host "=== [1/4] Build debug APK ===" -ForegroundColor Cyan
& .\gradlew.bat :app:assembleDebug --offline --console=plain
if ($LASTEXITCODE -ne 0) { Fail "build failed (EXITCODE=$LASTEXITCODE)" }
if (-not (Test-Path $APK_SRC)) { Fail ("APK not found: " + $APK_SRC) }

# ---------- 2. copy APK into server package ----------
Write-Host ""
Write-Host "=== [2/4] Copy APK to release_server/update ===" -ForegroundColor Cyan
if (-not (Test-Path $UPDATE_DIR)) { New-Item -ItemType Directory -Path $UPDATE_DIR | Out-Null }
Copy-Item -Path $APK_SRC -Destination $APK_DST -Force

# ---------- 3. write version.json ----------
Write-Host ""
Write-Host "=== [3/4] Write version.json ===" -ForegroundColor Cyan
$apkInfo = Get-Item $APK_DST
$sha = (Get-FileHash $APK_DST -Algorithm SHA256).Hash
$jsonObj = [ordered]@{
    versionCode = $versionCode
    versionName = $versionName
    apkFile     = "app-release.apk"
    size        = $apkInfo.Length
    sha256      = $sha
    notes       = $Notes
}
$jsonText = $jsonObj | ConvertTo-Json -Depth 3
$jsonPath = Join-Path $UPDATE_DIR 'version.json'
# UTF-8 without BOM so the client JSON parser does not choke on a BOM
[System.IO.File]::WriteAllText($jsonPath, $jsonText, (New-Object System.Text.UTF8Encoding($false)))
Write-Host ("version.json written: " + $jsonPath) -ForegroundColor Green
Write-Host ("  APK size: " + $apkInfo.Length + " bytes") -ForegroundColor Green
Write-Host ("  SHA256  : " + $sha) -ForegroundColor Green

# ---------- 4. optional: build & deploy server jar ----------
Write-Host ""
if ($DeployServer) {
    Write-Host "=== [4/4] Build & deploy server jar ===" -ForegroundColor Cyan
    & .\gradlew.bat :server:deployServer --offline --console=plain
    if ($LASTEXITCODE -ne 0) { Fail "server build failed (EXITCODE=$LASTEXITCODE)" }
    Write-Host "server jar updated: release_server/Re-NotTiled-Server.jar" -ForegroundColor Green
} else {
    Write-Host "=== [4/4] Skip server deploy (use -DeployServer to include) ===" -ForegroundColor DarkGray
}

Write-Host ""
Write-Host "Done. Update package ready in release_server/update/." -ForegroundColor Green
