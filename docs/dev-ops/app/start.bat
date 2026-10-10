@echo off
chcp 65001 >nul
title spoken-server (port 8091)

rem =====================================================================
rem spoken-server local launcher (Windows)
rem
rem Why chcp 65001 on line 2:
rem   The JVM logs in UTF-8 while the Windows console defaults to GBK(936),
rem   so without this every Chinese log line shows as mojibake.
rem
rem Why this file is pure ASCII:
rem   cmd parses a .bat using the console code page, so Chinese in a UTF-8
rem   .bat file gets split into garbage commands even if chcp comes first.
rem   Keep this file ASCII-only; put any Chinese notes in a README.
rem
rem LK888_API_KEY:
rem   It lives in the user-level environment. A freshly spawned process may
rem   not inherit it, so fall back to reading it from the registry here.
rem
rem App side: set the backend address to this machine's LAN IP, e.g.
rem   http://192.168.20.41:8091
rem =====================================================================

set "JAVA_HOME=D:\develop\evn\jdk"
set "JAR=D:\develop\code\project\jugao\spoken-server\spoken-server-app\target\ai-agent-scaffold-lite-app.jar"
set "WORKDIR=D:\develop\code\project\jugao\spoken-server"

if not exist "%JAR%" goto nojar

if "%LK888_API_KEY%"=="" (
  for /f "tokens=2,*" %%a in ('reg query "HKCU\Environment" /v LK888_API_KEY 2^>nul') do set "LK888_API_KEY=%%b"
)
if "%LK888_API_KEY%"=="" (
  echo [WARN] LK888_API_KEY is empty - the agent path will fail with 401
) else (
  echo [INFO] LK888_API_KEY injected
)

echo [INFO] starting spoken-server, workdir %WORKDIR%
cd /d "%WORKDIR%"
"%JAVA_HOME%\bin\java.exe" -Xmx1500m -Dfile.encoding=UTF-8 -jar "%JAR%"

echo.
echo [INFO] process exited
pause
exit /b 0

:nojar
echo [ERROR] jar not found: %JAR%
echo         build it first: mvn -DskipTests clean package
pause
exit /b 1
