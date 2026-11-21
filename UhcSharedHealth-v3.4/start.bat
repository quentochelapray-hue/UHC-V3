@echo off
title Serveur UHC
cd /d "%~dp0"

rem ===== Reglages =====
set RAM=3G
set JAR=server.jar
for %%f in (paper*.jar) do set JAR=%%f

set LEVEL=world
if exist server.properties (
  for /f "usebackq tokens=1,* delims==" %%a in ("server.properties") do (
    if "%%a"=="level-name" set LEVEL=%%b
  )
)

:start
echo.
echo === Demarrage du serveur (%JAR%) ===
java -Xms1G -Xmx%RAM% -jar "%JAR%" nogui

if exist reset.flag (
  echo.
  echo === Quelqu'un est mort : generation d'une nouvelle map ===
  del /q reset.flag
  rmdir /s /q "%LEVEL%" 2>nul
  rmdir /s /q "%LEVEL%_nether" 2>nul
  rmdir /s /q "%LEVEL%_the_end" 2>nul
  call :newseed
  timeout /t 3 /nobreak >nul
  goto start
)

echo.
echo Serveur arrete.
pause
goto :eof

rem ===== Nouvelle seed aleatoire ecrite dans server.properties (et notee dans seeds.log) =====
:newseed
for /f %%s in ('powershell -NoProfile -Command "Get-Random -Minimum -2000000000 -Maximum 2000000000"') do set SEED=%%s
powershell -NoProfile -Command "(Get-Content server.properties) -replace '^level-seed=.*', 'level-seed=%SEED%' | Set-Content server.properties"
echo Nouvelle seed : %SEED%
(echo %date% %time% seed=%SEED%)>> seeds.log
exit /b
