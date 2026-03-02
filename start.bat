@echo off
:: ============================================================
:: Android TV Time Fixer — Windows Launcher
:: Двойной клик запускает программу через PowerShell
:: Double-click opens the program in PowerShell
:: ============================================================

:: Переходим в каталог .bat файла
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

:: Запускаем в PowerShell с UTF-8 и в текущем окне
powershell.exe -NoLogo -ExecutionPolicy Bypass ^
    -Command "& { [Console]::OutputEncoding=[System.Text.Encoding]::UTF8; [Console]::InputEncoding=[System.Text.Encoding]::UTF8; $env:PYTHONIOENCODING='utf-8'; Set-Location '%~dp0'; & '.\AndroidTVTimeFixer.exe' }"
