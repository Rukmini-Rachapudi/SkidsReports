@echo off
REM Setup script to download Apache POI libraries

setlocal enabledelayedexpansion

echo.
echo ========================================
echo Apache POI Library Setup
echo ========================================
echo.

REM Check if lib folder exists
if not exist "lib" (
    echo Creating lib directory...
    mkdir lib
)

REM Try to download POI
echo Downloading Apache POI 5.2.5...
echo.

powershell -NoProfile -Command "try { $ProgressPreference = 'SilentlyContinue'; Invoke-WebRequest -Uri 'https://archive.apache.org/dist/poi/release/bin/poi-bin-5.2.5-20231214.zip' -OutFile 'poi.zip' -TimeoutSec 60; Write-Host 'Download complete' } catch { Write-Host 'Download failed'; exit 1 }"

if %ERRORLEVEL% neq 0 (
    echo.
    echo Download failed or timed out.
    echo.
    echo Please download manually:
    echo 1. Go to: https://poi.apache.org/download.html
    echo 2. Download the binary distribution (5.2.5 or latest)
    echo 3. Extract the ZIP file
    echo 4. Copy all JAR files from the 'lib' folder to:
    echo    %CD%\lib
    echo.
    pause
    exit /b 1
)

if exist poi.zip (
    echo Extracting files...
    powershell -NoProfile -Command "Expand-Archive -Path 'poi.zip' -DestinationPath '.' -Force"

    echo Copying JAR files...
    for /d %%d in (poi-bin-*) do (
        xcopy "%%d\lib\*.jar" "lib\" /Y /Q
        rmdir /s /q "%%d"
    )

    del poi.zip

    echo.
    echo ========================================
    echo Setup Complete!
    echo ========================================
    echo.
    echo JAR files are now in: %CD%\lib
    echo.
    echo Next steps in IntelliJ:
    echo 1. File ^> Invalidate Caches ^> Invalidate and Restart
    echo 2. Right-click on src/main/java folder
    echo 3. Select "Mark Directory as" ^> "Sources Root"
    echo 4. Run the application with Shift+F10
    echo.
    pause
) else (
    echo.
    echo Download failed. Please download manually:
    echo https://poi.apache.org/download.html
    echo.
    pause
    exit /b 1
)


