@echo off
echo ========================================
echo JOSM Assist Plugin - Build Setup
echo ========================================
echo.

REM Check JAVA_HOME
if "%JAVA_HOME%"=="" (
    echo WARNING: JAVA_HOME is not set!
    echo.
    echo You have Android Studio's JBR at: C:\Program Files\Android\Android Studio\jbr
    echo.
    echo Setting JAVA_HOME temporarily for this session...
    set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
    echo JAVA_HOME set to: %JAVA_HOME%
    echo.
) else (
    echo JAVA_HOME is set to: %JAVA_HOME%
    echo.
)

REM Verify Java
if exist "%JAVA_HOME%\bin\java.exe" (
    echo Java found at: %JAVA_HOME%\bin\java.exe
    "%JAVA_HOME%\bin\java.exe" -version
    echo.
) else (
    echo ERROR: Java not found at %JAVA_HOME%\bin\java.exe
    echo Please set JAVA_HOME to a valid JDK installation.
    pause
    exit /b 1
)

REM Check if Gradle wrapper exists
if not exist gradlew.bat (
    echo Gradle wrapper not found. Downloading...
    echo.
    echo Please download Gradle wrapper manually or install Gradle:
    echo 1. Download from: https://gradle.org/releases/
    echo 2. Or use: choco install gradle (if you have Chocolatey)
    echo.
    echo For now, we'll try to use system Gradle if available...
    gradle --version >nul 2>&1
    if %ERRORLEVEL% NEQ 0 (
        echo ERROR: Gradle not found!
        echo Please install Gradle or download the wrapper.
        pause
        exit /b 1
    )
)

echo.
echo Setup complete! You can now run: build.bat
echo.
pause

