$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $Root

$ExpectedSource = '5e3a1ed731ed0ff7ec42a2ce053e98dc18688759'
$GradleVersion = '8.10.2'
$GradleHome = Join-Path $Root ".build-tools\gradle-$GradleVersion"
$GradleZip = Join-Path $Root ".build-tools\gradle-$GradleVersion-bin.zip"
$GradleExe = Join-Path $GradleHome 'bin\gradle.bat'
$OutDir = Join-Path $Root 'VARDHANI_BUILD_OUTPUT'
$Apk = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'

function Fail([string]$Message) {
    Write-Host "`nBUILD FAILED: $Message" -ForegroundColor Red
    exit 1
}

Write-Host '============================================================'
Write-Host ' VARDHANI 1.0.0-full - VERIFIED WINDOWS APK BUILDER'
Write-Host '============================================================'
Write-Host "Expected development source baseline: $ExpectedSource"

if (-not (Test-Path (Join-Path $Root 'settings.gradle.kts'))) { Fail 'Run this from the VARDHANI repository root.' }
if (-not (Test-Path (Join-Path $Root 'app\build.gradle.kts'))) { Fail 'Android app module is missing.' }

# Prefer Android Studio's embedded JBR, otherwise an existing JAVA_HOME/java.
$StudioJbrCandidates = @(
    (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'),
    (Join-Path $env:ProgramFiles 'Android\Android Studio\jre')
)
foreach ($candidate in $StudioJbrCandidates) {
    if ($candidate -and (Test-Path (Join-Path $candidate 'bin\java.exe'))) {
        $env:JAVA_HOME = $candidate
        break
    }
}
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $java = Get-Command java.exe -ErrorAction SilentlyContinue
    if (-not $java) { Fail 'Java was not found. Install Android Studio with its bundled JDK.' }
} else {
    $env:Path = (Join-Path $env:JAVA_HOME 'bin') + ';' + $env:Path
}
Write-Host "JAVA_HOME: $env:JAVA_HOME"
& java -version
if ($LASTEXITCODE -ne 0) { Fail 'Java could not start.' }

# Detect Android SDK from environment, local.properties, or standard Windows location.
$sdk = $env:ANDROID_SDK_ROOT
if (-not $sdk) { $sdk = $env:ANDROID_HOME }
$localProps = Join-Path $Root 'local.properties'
if (-not $sdk -and (Test-Path $localProps)) {
    $line = Get-Content $localProps | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($line) { $sdk = ($line -replace '^sdk\.dir=', '').Replace('\\:', ':').Replace('\\\\','\') }
}
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path $sdk)) { Fail "Android SDK not found. Expected at $sdk. Install SDK Platform 35 in Android Studio SDK Manager." }
$env:ANDROID_SDK_ROOT = $sdk
$env:ANDROID_HOME = $sdk
Write-Host "ANDROID_SDK_ROOT: $sdk"

if (-not (Test-Path (Join-Path $sdk 'platforms\android-35\android.jar'))) {
    Fail 'Android SDK Platform 35 is missing. Install Android 15 / API 35 from Android Studio SDK Manager.'
}

# Supply local.properties deterministically for Gradle.
$sdkEscaped = $sdk.Replace('\','\\')
Set-Content -Path $localProps -Encoding ASCII -Value "sdk.dir=$sdkEscaped"

# Bootstrap exact Gradle release independently of a repository wrapper.
if (-not (Test-Path $GradleExe)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $GradleZip) | Out-Null
    Write-Host "Downloading Gradle $GradleVersion..."
    Invoke-WebRequest -UseBasicParsing -Uri "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip" -OutFile $GradleZip
    $hash = (Get-FileHash -Algorithm SHA256 $GradleZip).Hash.ToLowerInvariant()
    Write-Host "Gradle archive SHA-256: $hash"
    Expand-Archive -Force -Path $GradleZip -DestinationPath (Split-Path $GradleHome)
}
if (-not (Test-Path $GradleExe)) { Fail 'Gradle bootstrap failed.' }

Write-Host "Using Gradle $GradleVersion"
& $GradleExe --version
if ($LASTEXITCODE -ne 0) { Fail 'Gradle could not start.' }

Write-Host "`n[1/2] Running Android unit tests..."
& $GradleExe --no-daemon testDebugUnitTest --stacktrace
if ($LASTEXITCODE -ne 0) { Fail 'testDebugUnitTest failed. Review the console error above.' }

Write-Host "`n[2/2] Assembling debug APK..."
& $GradleExe --no-daemon assembleDebug --stacktrace
if ($LASTEXITCODE -ne 0) { Fail 'assembleDebug failed. Review the console error above.' }
if (-not (Test-Path $Apk)) { Fail 'Gradle completed but app-debug.apk was not created.' }
if ((Get-Item $Apk).Length -le 0) { Fail 'APK exists but is empty.' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
try {
    $hasDex = $false
    foreach ($entry in $zip.Entries) {
        if ($entry.FullName -eq 'classes.dex') { $hasDex = $true; break }
    }
    if (-not $hasDex) { Fail 'APK does not contain classes.dex.' }
} finally {
    $zip.Dispose()
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$FinalApk = Join-Path $OutDir 'VARDHANI-1.0.0-full-current.apk'
Copy-Item -Force $Apk $FinalApk
$Sha256 = (Get-FileHash -Algorithm SHA256 $FinalApk).Hash.ToLowerInvariant()
$Size = (Get-Item $FinalApk).Length
$Report = @(
    'VARDHANI 1.0.0-full Windows build verification',
    "Expected development source baseline: $ExpectedSource",
    'testDebugUnitTest: PASS',
    'assembleDebug: PASS',
    'classes.dex: PASS',
    "APK bytes: $Size",
    "SHA-256: $Sha256",
    "APK: $FinalApk"
)
$Report | Set-Content -Encoding UTF8 (Join-Path $OutDir 'VARDHANI_BUILD_REPORT.txt')

Write-Host "`n============================================================" -ForegroundColor Green
Write-Host ' VARDHANI APK BUILD + STRUCTURAL VERIFICATION PASSED' -ForegroundColor Green
Write-Host '============================================================' -ForegroundColor Green
Write-Host "APK: $FinalApk"
Write-Host "Bytes: $Size"
Write-Host "SHA-256: $Sha256"
Write-Host "Report: $(Join-Path $OutDir 'VARDHANI_BUILD_REPORT.txt')"
