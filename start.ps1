##############################################################
# Android TV Time Fixer — PowerShell Launcher
# Запускает AndroidTVTimeFixer.exe в текущем каталоге
##############################################################

param(
    [switch]$KeepOpenOnError
)

$ErrorActionPreference = "Stop"
$Host.UI.RawUI.WindowTitle = "Android TV Time Fixer"

# Определяем каталог скрипта (работает и при двойном клике)
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $scriptDir) {
    $scriptDir = (Get-Location).Path
}

$exeName = "AndroidTVTimeFixer.exe"
$exePath = Join-Path $scriptDir $exeName

if (-not (Test-Path $exePath)) {
    Write-Host ""
    Write-Host "ERROR: $exeName not found in:" -ForegroundColor Red
    Write-Host "  $scriptDir" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Make sure start.bat / start.ps1 is in the same folder as the executable." -ForegroundColor Cyan
    Read-Host "Press Enter to close"
    exit 1
}

Set-Location $scriptDir

# Устанавливаем UTF-8, чтобы корректно отображались кириллица и спецсимволы
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding  = [System.Text.Encoding]::UTF8
$env:PYTHONIOENCODING = "utf-8"

try {
    & $exePath
    $exitCode = $LASTEXITCODE
    if ($null -eq $exitCode) {
        $exitCode = 0
    }
} catch {
    $exitCode = 1
    Write-Host ""
    Write-Host "ERROR: Failed to start $exeName" -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Yellow
}

if ($KeepOpenOnError -and $exitCode -ne 0) {
    Write-Host ""
    Write-Host "$exeName exited with code $exitCode." -ForegroundColor Red
    Write-Host "Check the messages above or android_tv_fixer.log in this folder." -ForegroundColor Cyan
    Read-Host "Press Enter to close"
}

exit $exitCode
