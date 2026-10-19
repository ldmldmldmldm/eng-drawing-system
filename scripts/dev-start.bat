@echo off
REM ------------------------------------------------------------------
REM  eng-drawing local dev launcher (Windows)
REM  1) DB   : docker compose mysql  -> 127.0.0.1:23306 (root/root)
REM  2) API  : eng-admin.jar          -> 127.0.0.1:6053
REM  3) WEB  : vite dev               -> 127.0.0.1:8893
REM ------------------------------------------------------------------
setlocal
cd /d "%~dp0.."
set "ROOT=%CD%"

REM --- locate java (prefer JAVA_HOME, then common install, then PATH) ---
set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE if exist "C:\Program Files\Java\latest\jdk-21\bin\java.exe" set "JAVA_EXE=C:\Program Files\Java\latest\jdk-21\bin\java.exe"
if not defined JAVA_EXE for /f "delims=" %%i in ('where java 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%i"
if not defined JAVA_EXE (
  echo [ERROR] java not found. Install JDK 17+ or set JAVA_HOME.
  pause & exit /b 1
)

REM --- locate python with ezdxf/matplotlib ---
set "PY_EXE=%USERPROFILE%\.workbuddy\binaries\python\envs\eng-drawing\Scripts\python.exe"
if not exist "%PY_EXE%" (
  echo [WARN] venv python not found, falling back to "python".
  set "PY_EXE=python"
)

REM --- locate docker ---
set "DOCKER=docker"
if exist "%LOCALAPPDATA%\Programs\DockerDesktop\resources\bin\docker.exe" set "DOCKER=%LOCALAPPDATA%\Programs\DockerDesktop\resources\bin\docker.exe"

REM --- clean host-injected overrides that break Spring port binding ---
set "SERVER__PORT="
set "SERVER__HOST="

REM --- app runtime env (inherited by the backend window) ---
set "AI_DRAWING_DIR=%ROOT%\data\eng-drawing"
set "AI_DRAWING_PYTHON=%PY_EXE%"
set "LOG_PATH=%ROOT%\logs"

if not exist "%ROOT%\eng-backend\eng-admin\target\eng-admin.jar" (
  echo [ERROR] eng-admin.jar missing. Build first:
  echo         cd eng-backend ^&^& mvn -pl eng-admin -am package -DskipTests
  pause & exit /b 1
)

echo [1/3] starting MySQL container ...
"%DOCKER%" compose -f docker-compose.local.yml up -d mysql
if errorlevel 1 (
  echo [ERROR] docker compose failed. Is Docker Desktop running?
  pause & exit /b 1
)

echo [2/3] starting backend on 6053 ...
start "eng-backend" cmd /k ""%JAVA_EXE%" -Dfile.encoding=UTF-8 -jar "%ROOT%\eng-backend\eng-admin\target\eng-admin.jar" --spring.profiles.active=dev --server.port=6053"

echo [3/3] starting frontend on 8893 ...
start "eng-frontend" cmd /k "cd /d "%ROOT%\eng-frontend" && npm run dev"

echo.
echo   Web : http://127.0.0.1:8893
echo   API : http://127.0.0.1:6053/health
echo   User: admin / admin@123
echo.
pause
