@echo off
echo ========================================
echo Building JOSM Assist Plugin
echo ========================================
echo.

REM Set JAVA_HOME if not set (use Android Studio's JBR)
if "%JAVA_HOME%"=="" (
    if exist "C:\Program Files\Android\Android Studio\jbr" (
        set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
        echo JAVA_HOME not set, using Android Studio JBR: %JAVA_HOME%
    ) else (
        echo ERROR: JAVA_HOME is not set!
        echo Please set JAVA_HOME to your JDK installation directory.
        echo Example: set JAVA_HOME=C:\Program Files\Java\jdk1.8.0_XXX
        pause
        exit /b 1
    )
) else (
    echo JAVA_HOME: %JAVA_HOME%
)
echo.

REM Check if Gradle wrapper jar exists, download if missing
if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo Gradle wrapper jar not found. Downloading...
    powershell -Command "Invoke-WebRequest -Uri 'https://raw.githubusercontent.com/gradle/gradle/v7.6.0/gradle/wrapper/gradle-wrapper.jar' -OutFile 'gradle\wrapper\gradle-wrapper.jar'"
    if %ERRORLEVEL% NEQ 0 (
        echo Failed to download Gradle wrapper. Trying system Gradle...
        gradle build
        goto :build_result
    )
)

REM Use Gradle wrapper
if exist gradlew.bat (
    echo Using Gradle Wrapper...
    call gradlew.bat build
) else (
    echo Gradle Wrapper script not found. Using system Gradle...
    gradle build
)

:build_result

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================
    echo Build successful!
    echo ========================================
    echo.
    echo JAR file created at: build\libs\josmassist-1.0.jar
    echo.
    echo To install the plugin:
    echo 1. Copy the JAR file to: %APPDATA%\JOSM\plugins\
    echo 2. Restart JOSM or go to Edit ^> Preferences ^> Plugins
    echo 3. Enable "JOSM Assist" plugin
    echo.
    echo Would you like to copy the JAR to the plugins directory now? (Y/N)
    set /p COPY_CHOICE=
    if /i "%COPY_CHOICE%"=="Y" (
        if not exist "%APPDATA%\JOSM\plugins" (
            mkdir "%APPDATA%\JOSM\plugins"
            echo Created plugins directory: %APPDATA%\JOSM\plugins
        )
        copy /Y build\libs\josmassist-1.0.jar "%APPDATA%\JOSM\plugins\"
        if %ERRORLEVEL% EQU 0 (
            echo.
            echo Plugin copied successfully!
            echo You can now restart JOSM or enable it in Preferences ^> Plugins
        ) else (
            echo.
            echo Failed to copy plugin. Please copy manually.
        )
    )
    echo.
    pause
) else (
    echo.
    echo ========================================
    echo Build failed!
    echo ========================================
    echo.
    pause
    exit /b 1
)

