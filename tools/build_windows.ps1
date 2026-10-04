param(
    [string]$RunDir = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = Split-Path -Parent $PSScriptRoot
$OutDir = Join-Path $Root 'out'
$ApkSource = Join-Path $Root 'app\build\outputs\apk\debug\app-debug.apk'
$ApkTarget = Join-Path $OutDir 'MK15PortInspector-1.5.0-debug.apk'
$AarSource = Join-Path $Root 'mk15-sdk\build\outputs\aar\mk15-sdk-release.aar'
$AarTarget = Join-Path $OutDir 'MK15-CD-SDK-1.0.0.aar'
$ToolsDir = Join-Path $Root '.tools'
$GradleVersion = '8.7'
$GradleZip = Join-Path $ToolsDir ("gradle-" + $GradleVersion + "-bin.zip")
$GradleHome = Join-Path $ToolsDir ("gradle-" + $GradleVersion)
$GradleBat = Join-Path $GradleHome 'bin\gradle.bat'

function Fail([string]$Message) {
    Write-Host ''
    Write-Host ('ERROR: ' + $Message) -ForegroundColor Red
    throw $Message
}

function Find-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
        return $env:JAVA_HOME
    }
    $AndroidStudioJbr = 'C:\Program Files\Android\Android Studio\jbr'
    if (Test-Path (Join-Path $AndroidStudioJbr 'bin\java.exe')) {
        return $AndroidStudioJbr
    }
    return $null
}

function Find-AndroidSdk {
    if ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) {
        return $env:ANDROID_SDK_ROOT
    }
    if ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) {
        return $env:ANDROID_HOME
    }
    if ($env:LOCALAPPDATA) {
        $DefaultSdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
        if (Test-Path $DefaultSdk) {
            return $DefaultSdk
        }
    }
    return $null
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
New-Item -ItemType Directory -Force -Path $ToolsDir | Out-Null

Write-Host '=== MK15 Port Inspector: Windows build ==='
Write-Host ('Project: ' + $Root)

$JavaHome = Find-JavaHome
if (-not $JavaHome) {
    Fail 'JDK was not found. Install Android Studio or set JAVA_HOME to a JDK 17 installation.'
}

$env:JAVA_HOME = $JavaHome
$env:Path = (Join-Path $JavaHome 'bin') + ';' + $env:Path
Write-Host ('JAVA_HOME=' + $env:JAVA_HOME)
& (Join-Path $JavaHome 'bin\java.exe') -version
if ($LASTEXITCODE -ne 0) {
    Fail ('java -version returned exit code ' + $LASTEXITCODE)
}

$Sdk = Find-AndroidSdk
if (-not $Sdk) {
    Fail 'Android SDK was not found. Install Android Studio/SDK or set ANDROID_SDK_ROOT.'
}

$env:ANDROID_SDK_ROOT = $Sdk
$env:ANDROID_HOME = $Sdk
Write-Host ('ANDROID_SDK_ROOT=' + $Sdk)

$AndroidJar = Join-Path $Sdk 'platforms\android-34\android.jar'
$Aapt2 = Join-Path $Sdk 'build-tools\34.0.0\aapt2.exe'
if (-not (Test-Path $AndroidJar) -or -not (Test-Path $Aapt2)) {
    Write-Host ''
    Write-Host 'Android SDK Platform 34 and/or Build-Tools 34.0.0 are missing.' -ForegroundColor Yellow
    Write-Host 'Install them in Android Studio SDK Manager or run:'
    Write-Host '  sdkmanager.bat "platforms;android-34" "build-tools;34.0.0" "platform-tools"'
    Fail 'Required Android SDK components are missing.'
}

$NdkVersion = '28.2.13676358'
$NdkRoot = Join-Path $Sdk ('ndk\' + $NdkVersion)
if (-not (Test-Path $NdkRoot)) {
    Write-Host ''
    Write-Host ('Android NDK ' + $NdkVersion + ' is missing. Installing it once...') -ForegroundColor Yellow
    $SdkManager = $null
    $SdkManagerCmd = Get-Command sdkmanager.bat -ErrorAction SilentlyContinue
    if ($SdkManagerCmd) {
        $SdkManager = $SdkManagerCmd.Source
    }
    if (-not $SdkManager) {
        $CmdlineRoot = Join-Path $Sdk 'cmdline-tools'
        if (Test-Path $CmdlineRoot) {
            $SdkManagerFile = Get-ChildItem -Path $CmdlineRoot -Filter 'sdkmanager.bat' -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($SdkManagerFile) { $SdkManager = $SdkManagerFile.FullName }
        }
    }
    if (-not $SdkManager) {
        Fail ('sdkmanager.bat was not found. Install Android command-line tools, then install ndk;' + $NdkVersion)
    }
    & $SdkManager ('--sdk_root=' + $Sdk) ('ndk;' + $NdkVersion)
    if ($LASTEXITCODE -ne 0) {
        Fail ('sdkmanager failed to install ndk;' + $NdkVersion + ' rc=' + $LASTEXITCODE)
    }
}
if (-not (Test-Path $NdkRoot)) {
    Fail ('NDK installation finished but directory was not found: ' + $NdkRoot)
}
Write-Host ('ANDROID_NDK=' + $NdkRoot)

Write-Host ''
Write-Host 'Building native UART library with clang (without ndk-build)...'
& (Join-Path $PSScriptRoot 'build_native_windows.ps1') -SdkRoot $Sdk -NdkVersion $NdkVersion
if ($LASTEXITCODE -ne 0) {
    Fail ('Native UART build returned exit code ' + $LASTEXITCODE)
}

$HostBuild = Join-Path $Root '.host-test-build'
if (Test-Path $HostBuild) {
    Remove-Item -Recurse -Force $HostBuild
}
New-Item -ItemType Directory -Force -Path $HostBuild | Out-Null

$Javac = Join-Path $JavaHome 'bin\javac.exe'
$Java = Join-Path $JavaHome 'bin\java.exe'
$ProtocolSource = Join-Path $Root 'app\src\main\java\com\mk15\portinspector\SiyiProtocol.java'
$DiffSource = Join-Path $Root 'app\src\main\java\com\mk15\portinspector\ProbeDiffEngine.java'
$ReportSource = Join-Path $Root 'app\src\main\java\com\mk15\portinspector\ReportTools.java'
$ActivitySource = Join-Path $Root 'app\src\main\java\com\mk15\portinspector\ChannelActivityTracker.java'
$HardwareResearchSource = Join-Path $Root 'app\src\main\java\com\mk15\portinspector\HardwareControlsResearch.java'
$SdkProtocolSource = Join-Path $Root 'mk15-sdk\src\main\java\com\mk15\sdk\Mk15Protocol.java'
$SdkEvidenceSource = Join-Path $Root 'mk15-sdk\src\main\java\com\mk15\sdk\Mk15Evidence.java'
$ProtocolTest = Join-Path $Root 'host-tests\ProtocolSelfTest.java'
$DiffTest = Join-Path $Root 'host-tests\ProbeDiffSelfTest.java'
$ReportTest = Join-Path $Root 'host-tests\ReportToolsSelfTest.java'
$ActivityTest = Join-Path $Root 'host-tests\ChannelActivityTrackerSelfTest.java'
$SdkTest = Join-Path $Root 'host-tests\Mk15SdkSelfTest.java'
$HardwareResearchTest = Join-Path $Root 'host-tests\HardwareControlsResearchSelfTest.java'

Write-Host ''
Write-Host 'Running SIYI protocol self-test...'
& $Javac -encoding UTF-8 -d $HostBuild $ProtocolSource $DiffSource $ReportSource $ActivitySource $HardwareResearchSource $SdkProtocolSource $SdkEvidenceSource $ProtocolTest $DiffTest $ReportTest $ActivityTest $SdkTest $HardwareResearchTest
if ($LASTEXITCODE -ne 0) {
    Fail ('javac protocol test compile returned exit code ' + $LASTEXITCODE)
}

& $Java -cp $HostBuild ProtocolSelfTest
if ($LASTEXITCODE -ne 0) {
    Fail ('ProtocolSelfTest returned exit code ' + $LASTEXITCODE)
}
& $Java -cp $HostBuild ProbeDiffSelfTest
if ($LASTEXITCODE -ne 0) {
    Fail ('ProbeDiffSelfTest returned exit code ' + $LASTEXITCODE)
}
& $Java -cp $HostBuild ReportToolsSelfTest
if ($LASTEXITCODE -ne 0) {
    Fail ('ReportToolsSelfTest returned exit code ' + $LASTEXITCODE)
}
& $Java -cp $HostBuild ChannelActivityTrackerSelfTest
if ($LASTEXITCODE -ne 0) {
    Fail ('ChannelActivityTrackerSelfTest returned exit code ' + $LASTEXITCODE)
}
& $Java -cp $HostBuild Mk15SdkSelfTest
if ($LASTEXITCODE -ne 0) {
    Fail ('Mk15SdkSelfTest returned exit code ' + $LASTEXITCODE)
}
& $Java -cp $HostBuild HardwareControlsResearchSelfTest
if ($LASTEXITCODE -ne 0) {
    Fail ('HardwareControlsResearchSelfTest returned exit code ' + $LASTEXITCODE)
}

$GradleCmd = Get-Command gradle.bat -ErrorAction SilentlyContinue
if ($GradleCmd) {
    $Gradle = $GradleCmd.Source
    Write-Host ('Using Gradle from PATH: ' + $Gradle)
}
else {
    if (-not (Test-Path $GradleBat)) {
        Write-Host ("Gradle " + $GradleVersion + " was not found. Downloading official distribution...")
        if (-not (Test-Path $GradleZip)) {
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
            $Url = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"
            Invoke-WebRequest -Uri $Url -OutFile $GradleZip -UseBasicParsing
        }
        if (Test-Path $GradleHome) {
            Remove-Item -Recurse -Force $GradleHome
        }
        Expand-Archive -Path $GradleZip -DestinationPath $ToolsDir -Force
    }

    if (-not (Test-Path $GradleBat)) {
        Fail 'Gradle archive was extracted but gradle.bat was not found.'
    }

    $Gradle = $GradleBat
    Write-Host ('Using local Gradle: ' + $Gradle)
}

Push-Location $Root
try {
    Write-Host ''
    Write-Host 'Running Android debug build...'
    & $Gradle ':app:assembleDebug' ':mk15-sdk:assembleRelease' '--no-daemon' '--stacktrace'
    if ($LASTEXITCODE -ne 0) {
        Fail ('Gradle returned exit code ' + $LASTEXITCODE)
    }
}
finally {
    Pop-Location
}

if (-not (Test-Path $ApkSource)) {
    Fail ('Gradle reported success but APK was not found: ' + $ApkSource)
}
if (-not (Test-Path $AarSource)) {
    Fail ('Gradle reported success but SDK AAR was not found: ' + $AarSource)
}

Copy-Item -Force $ApkSource $ApkTarget
Copy-Item -Force $AarSource $AarTarget
$ApkHash = (Get-FileHash -Algorithm SHA256 $ApkTarget).Hash
$AarHash = (Get-FileHash -Algorithm SHA256 $AarTarget).Hash

Write-Host ''
Write-Host 'BUILD SUCCESSFUL.' -ForegroundColor Green
Write-Host ('APK: ' + $ApkTarget)
Write-Host ('APK SHA256: ' + $ApkHash)
Write-Host ('SDK AAR: ' + $AarTarget)
Write-Host ('SDK SHA256: ' + $AarHash)
