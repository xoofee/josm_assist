@echo off
echo ========================================
echo Downloading JOSM JAR for plugin development
echo ========================================
echo.

if not exist libs mkdir libs

echo Downloading latest JOSM JAR...
echo This will be used as a compile-time dependency.
echo.

REM Download JOSM JAR from official site
powershell -Command "Invoke-WebRequest -Uri 'https://josm.openstreetmap.de/josm-tested.jar' -OutFile 'libs\josm.jar'"

if %ERRORLEVEL% EQU 0 (
    echo.
    echo JOSM JAR downloaded successfully to: libs\josm.jar
    echo You can now build the plugin using: build.bat
) else (
    echo.
    echo Failed to download JOSM JAR automatically.
    echo.
    echo Please download manually:
    echo 1. Go to: https://josm.openstreetmap.de/
    echo 2. Download the JOSM JAR file (josm-tested.jar or josm-latest.jar)
    echo 3. Save it as: libs\josm.jar
    echo.
)

echo.
pause

