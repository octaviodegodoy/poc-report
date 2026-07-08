@echo off
REM =====================================================================
REM SUBIR a PoC de Ingestao TB_JSON_TRANSACAO (modo PostgreSQL)
REM - Banco PostgreSQL em Docker (localhost:5432)
REM - Backend Spring Boot na porta 8080 (profile padrao)
REM - Frontend React/Vite na porta 5173
REM =====================================================================
setlocal
cd /d "%~dp0"

echo.
echo ======== INICIANDO PoC Ingestao (PostgreSQL) ========
echo.

REM ---- Sanitiza JAVA_HOME: remove trailing newlines/spaces que quebram o parser do CMD ----
REM     Causa comum: variavel de ambiente gerada por script que adiciona LF no final.
if defined JAVA_HOME (
    for /f "usebackq tokens=*" %%J in (`powershell -NoProfile -Command "$env:JAVA_HOME.Trim()"`) do set "JAVA_HOME=%%J"
)

REM ---- Java: valida JAVA_HOME, tenta fallback e falha com mensagem clara ----
set "JAVA_CANDIDATE="

if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\javac.exe" (
        set "JAVA_CANDIDATE=%JAVA_HOME%"
    )
)

if not defined JAVA_CANDIDATE (
    if exist "C:\Program Files\Java\latest\bin\javac.exe" set "JAVA_CANDIDATE=C:\Program Files\Java\latest"
)

if not defined JAVA_CANDIDATE (
    for /f "delims=" %%D in ('dir /b /ad "C:\Program Files\Java\jdk-*" 2^>nul') do (
        if exist "C:\Program Files\Java\%%D\bin\javac.exe" set "JAVA_CANDIDATE=C:\Program Files\Java\%%D"
    )
)

if not defined JAVA_CANDIDATE (
    if exist "C:\Program Files\Java\jdk-21\bin\javac.exe" set "JAVA_CANDIDATE=C:\Program Files\Java\jdk-21"
)

if not defined JAVA_CANDIDATE (
    if exist "C:\Program Files\Java\jdk-17\bin\javac.exe" set "JAVA_CANDIDATE=C:\Program Files\Java\jdk-17"
)

if not defined JAVA_CANDIDATE (
    for /f "delims=" %%I in ('where javac 2^>nul') do (
        set "JAVAC_PATH=%%~fI"
        goto :resolve_java_home
    )
)

goto :after_java_check

:resolve_java_home
for %%P in ("%JAVAC_PATH%") do set "JAVA_BIN_DIR=%%~dpP"
for %%P in ("%JAVA_BIN_DIR%..") do set "JAVA_CANDIDATE=%%~fP"

:after_java_check
if not defined JAVA_CANDIDATE (
    echo.
    echo ERRO: JAVA_HOME nao encontrado.
    echo Configure a variavel JAVA_HOME para a pasta do JDK e adicione %%JAVA_HOME%%\bin no PATH.
    echo Exemplo: C:\Program Files\Java\latest
    echo.
    exit /b 1
)

set "JAVA_HOME=%JAVA_CANDIDATE%"
echo JAVA_HOME detectado: %JAVA_HOME%
set "BACKEND_DIR=%CD%\backend"

REM ---- 1) Banco (Docker Compose) ----
echo [1/3] Subindo PostgreSQL (Docker)...
docker compose up -d
if errorlevel 1 (
    echo.
    echo ERRO: falha ao subir PostgreSQL com Docker Compose.
    echo Verifique se o Docker Desktop esta em execucao.
    echo.
    exit /b 1
)

REM ---- 2) Backend (Maven Wrapper, profile padrao) ----
echo [2/3] Compilando e iniciando Backend...
start "PoC Ingestao - Backend" powershell -NoExit -Command "$env:JAVA_HOME='%JAVA_HOME%'; Set-Location '%BACKEND_DIR%'; & '%JAVA_HOME%\bin\java.exe' -classpath '.mvn\wrapper\maven-wrapper.jar' '-Dmaven.multiModuleProjectDirectory=%BACKEND_DIR%' org.apache.maven.wrapper.MavenWrapperMain spring-boot:run"

REM ---- aguarda backend inicializar (porta 8080) ----
set /a BACKEND_WAIT=0
set /a BACKEND_TIMEOUT=180
echo      Aguardando Backend responder em http://localhost:8080 ...

:wait_backend
powershell -NoProfile -Command "$c=New-Object Net.Sockets.TcpClient; try{$iar=$c.BeginConnect('127.0.0.1',8080,$null,$null); if($iar.AsyncWaitHandle.WaitOne(1000,$false) -and $c.Connected){$c.EndConnect($iar); exit 0}else{exit 1}} catch {exit 1} finally {$c.Close()}" >nul 2>nul
if not errorlevel 1 goto backend_ready

set /a BACKEND_WAIT+=2
if %BACKEND_WAIT% GEQ %BACKEND_TIMEOUT% goto backend_timeout

echo      Backend ainda inicializando... %BACKEND_WAIT%s
timeout /t 2 /nobreak >nul
goto wait_backend

:backend_ready
echo      Backend pronto.
goto start_frontend

:backend_timeout
echo      AVISO: backend nao respondeu em %BACKEND_TIMEOUT%s. O frontend sera iniciado mesmo assim.

:start_frontend

REM ---- npm/Node: valida antes de iniciar o Frontend ----
set "NPM_CMD="
set "NODEJS_DIR="

for /f "delims=" %%I in ('where npm 2^>nul') do (
    set "NPM_CMD=%%~fI"
    goto :after_npm_check
)

if exist "%ProgramFiles%\nodejs\npm.cmd" (
    set "NPM_CMD=%ProgramFiles%\nodejs\npm.cmd"
    set "NODEJS_DIR=%ProgramFiles%\nodejs"
)

if not defined NPM_CMD (
    if exist "%ProgramFiles(x86)%\nodejs\npm.cmd" (
        set "NPM_CMD=%ProgramFiles(x86)%\nodejs\npm.cmd"
        set "NODEJS_DIR=%ProgramFiles(x86)%\nodejs"
    )
)

if not defined NPM_CMD (
    if exist "%LocalAppData%\Programs\nodejs\npm.cmd" (
        set "NPM_CMD=%LocalAppData%\Programs\nodejs\npm.cmd"
        set "NODEJS_DIR=%LocalAppData%\Programs\nodejs"
    )
)

:after_npm_check
if defined NODEJS_DIR (
    set "PATH=%NODEJS_DIR%;%PATH%"
)

REM ---- 3) Frontend ----
if not defined NPM_CMD (
    echo [3/3] Frontend nao iniciado: npm nao encontrado.
    echo Instale Node.js e garanta npm no PATH para subir o frontend.
) else (
    echo [3/3] Iniciando Frontend...
    echo npm detectado: %NPM_CMD%
    if not exist "frontend\node_modules" (
        start "PoC Ingestao - Frontend" cmd /k "set PATH=%PATH% && cd frontend && call ^"%NPM_CMD%^" install && call ^"%NPM_CMD%^" run dev"
    ) else (
        start "PoC Ingestao - Frontend" cmd /k "set PATH=%PATH% && cd frontend && call ^"%NPM_CMD%^" run dev"
    )
)

echo.
echo ======== PoC Ingestao iniciada! ========
echo.
echo  Banco    : PostgreSQL (localhost:5432, db ingestao)
echo  Backend  : http://localhost:8080
echo  Frontend : http://localhost:5173
echo.
echo Use stop.bat para baixar tudo.
echo.
endlocal
