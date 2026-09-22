@echo off
REM Build the Sakura phone app (debug + release APK).
REM
REM Usage:  phone_app\build_apk.bat
REM
REM ASCII-only on purpose: cmd reads batch files using the console code page,
REM so non-ASCII characters here would break on some systems.
REM
REM You can override any of these before running:
REM     set ANDROID_SDK_ROOT=D:\Android\Sdk
REM     set JAVA_HOME=C:\Program Files\Java\jdk-21

setlocal

set "APP_ROOT=%~dp0"
if "%APP_ROOT:~-1%"=="\" set "APP_ROOT=%APP_ROOT:~0,-1%"

set "ANDROID_ROOT=%APP_ROOT%\android"
set "GRADLE_USER_HOME=%APP_ROOT%\.gradle"

REM ---------------------------------------------------------------------------
REM Locate the Android SDK.
REM   1. ANDROID_SDK_ROOT / ANDROID_HOME if already set
REM   2. the usual per-user location
REM   3. a local junction (.android-sdk) created by setup_sdk.bat
REM ---------------------------------------------------------------------------
if not defined ANDROID_SDK_ROOT if defined ANDROID_HOME set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
if not defined ANDROID_SDK_ROOT if exist "%LOCALAPPDATA%\Android\Sdk\platforms" set "ANDROID_SDK_ROOT=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_SDK_ROOT if exist "%APP_ROOT%\.android-sdk\platforms" set "ANDROID_SDK_ROOT=%APP_ROOT%\.android-sdk"

if not defined ANDROID_SDK_ROOT (
    echo [ERROR] Android SDK not found.
    echo.
    echo   Set it once, then re-run:
    echo       set ANDROID_SDK_ROOT=D:\Android\Sdk
    echo.
    echo   Need platform 36 and build-tools installed.
    exit /b 1
)

REM Gradle reads local.properties as ISO-8859-1, so a non-ASCII sdk.dir becomes
REM mojibake and the build fails. If the SDK path contains non-ASCII characters,
REM create an ASCII junction and point sdk.dir at that instead.
set "SDK_FOR_GRADLE=%ANDROID_SDK_ROOT%"
echo %ANDROID_SDK_ROOT% | findstr /r /c:"^[ -~]*$" >nul
if errorlevel 1 (
    set "SDK_FOR_GRADLE=%APP_ROOT%\.android-sdk"
    if not exist "%SDK_FOR_GRADLE%\platforms" (
        echo [INFO] SDK path has non-ASCII characters; creating an ASCII junction.
        mklink /J "%SDK_FOR_GRADLE%" "%ANDROID_SDK_ROOT%" >nul
    )
)

if not exist "%ANDROID_SDK_ROOT%\platforms\android-36\android.jar" (
    echo [ERROR] Android platform 36 not found under %ANDROID_SDK_ROOT%
    exit /b 1
)

REM local.properties must escape backslashes.
set "SDK_ESCAPED=%SDK_FOR_GRADLE:\=\\%"
> "%ANDROID_ROOT%\local.properties" echo sdk.dir=%SDK_ESCAPED%

REM ---------------------------------------------------------------------------
REM Gradle: prefer a locally unpacked distribution, else the wrapper.
REM ---------------------------------------------------------------------------
set "GRADLE=%APP_ROOT%\.gradle-dist\gradle-8.14.3\bin\gradle.bat"
if not exist "%GRADLE%" set "GRADLE=%ANDROID_ROOT%\gradlew.bat"

REM Optional mirror init-script (helps where services.gradle.org is blocked).
set "INIT="
if exist "%APP_ROOT%\gradle-mirror.init.gradle" set "INIT=--init-script "%APP_ROOT%\gradle-mirror.init.gradle""

echo App folder  : %APP_ROOT%
echo Android SDK : %ANDROID_SDK_ROOT%
if not "%SDK_FOR_GRADLE%"=="%ANDROID_SDK_ROOT%" echo SDK junction : %SDK_FOR_GRADLE%
echo Gradle      : %GRADLE%
echo.

pushd "%ANDROID_ROOT%"
call "%GRADLE%" --no-daemon %INIT% assembleDebug assembleRelease
set "CODE=%ERRORLEVEL%"
popd

echo.
if not "%CODE%"=="0" (
    echo BUILD FAILED ^(gradle exit %CODE%^)
    exit /b %CODE%
)

echo BUILD OK
echo   debug   : %ANDROID_ROOT%\app\build\outputs\apk\debug\app-debug.apk
echo   release : %ANDROID_ROOT%\app\build\outputs\apk\release\app-release.apk
exit /b 0
