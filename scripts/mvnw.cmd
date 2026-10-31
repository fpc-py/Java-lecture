@echo off
REM ---------------------------------------------------------------------------
REM Polaris . Maven wrapper (PowerShell / CMD)
REM 进程内切换 JAVA_HOME 到 JDK 21，不改系统环境变量。见 scripts/mvnw.sh 注释。
REM 用法: scripts\mvnw.cmd -v
REM ---------------------------------------------------------------------------
setlocal

if "%POLARIS_JDK21%"=="" set "POLARIS_JDK21=D:\app\jdk-21.0.12.1"
if not exist "%POLARIS_JDK21%\bin\javac.exe" (
  echo [mvnw] JDK 21 not found at %POLARIS_JDK21%
  exit /b 1
)

set "JAVA_HOME=%POLARIS_JDK21%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

for /f "tokens=3" %%v in ('javac -version 2^>^&1') do set "JAVAC_VER=%%v"
echo %JAVAC_VER% | findstr /b "21." >nul || (
  echo [mvnw] javac is %JAVAC_VER%, expected 21.x
  exit /b 1
)

call mvn %*
endlocal
