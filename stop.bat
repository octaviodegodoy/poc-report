@echo off
REM =====================================================================
REM BAIXAR a PoC de Ingestao TB_JSON_TRANSACAO (modo PostgreSQL)
REM - Encerra janelas do Backend e Frontend
REM - Derruba o banco PostgreSQL em Docker
REM =====================================================================
setlocal
cd /d "%~dp0"

echo.
echo ======== PARANDO PoC Ingestao ========
echo.

REM ---- Encerra janelas abertas pelo start.bat ----
echo [1/3] Encerrando Backend...
taskkill /FI "WINDOWTITLE eq PoC Ingestao - Backend*" /T /F >nul 2>nul

echo [2/3] Encerrando Frontend...
taskkill /FI "WINDOWTITLE eq PoC Ingestao - Frontend*" /T /F >nul 2>nul

echo [3/3] Derrubando PostgreSQL (Docker)...
docker compose down >nul 2>nul

echo.
echo ======== PoC Ingestao parada! ========
echo.
echo Para remover tambem o volume de dados do Postgres, execute:
echo   docker compose down -v
echo.
endlocal
