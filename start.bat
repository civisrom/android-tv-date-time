@echo off
:: ============================================================
:: Android TV Time Fixer — Windows Launcher
:: Double-click opens the program in a new PowerShell window
:: Двойной клик открывает программу в окне PowerShell
:: ============================================================

cd /d "%~dp0"

:: Проверяем существование exe
if not exist "%~dp0AndroidTVTimeFixer.exe" (
    echo.
    echo  ERROR: AndroidTVTimeFixer.exe not found in:
    echo  %~dp0
    echo.
    echo  Place start.bat in the same folder as AndroidTVTimeFixer.exe
    echo.
    pause
    exit /b 1
)

:: Открываем НОВОЕ окно PowerShell и закрываем cmd
start "Android TV Time Fixer" powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1" -KeepOpenOnError
exit
