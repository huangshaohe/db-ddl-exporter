@echo off
setlocal
cd /d "%~dp0"
where java >nul 2>&1
if errorlevel 1 (
  echo Java 8 or newer is required. Install Java and add it to PATH.
  pause
  exit /b 1
)
set "APP_JAR=%~dp0db-ddl-exporter.jar"
if not exist "%APP_JAR%" set "APP_JAR=%~dp0target\db-ddl-exporter.jar"
if not exist "%APP_JAR%" set "APP_JAR=%~dp0kingbase-ddl-exporter.jar"
if not exist "%APP_JAR%" set "APP_JAR=%~dp0target\kingbase-ddl-exporter.jar"
if not exist "%APP_JAR%" (
  echo Application JAR not found. Run mvn clean package first.
  pause
  exit /b 1
)
java -jar "%APP_JAR%" %*
if errorlevel 1 pause
