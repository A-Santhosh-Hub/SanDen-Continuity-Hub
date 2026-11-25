@echo off
title SanDen Continuity Hub — Portable Native Windows Builder
echo =========================================================
echo  Building SanDen Native Windows Desktop Application...
echo =========================================================

dotnet publish "%~dp0SanDen.Desktop\SanDen.Desktop.csproj" ^
  -c Release ^
  -r win-x64 ^
  --self-contained true ^
  -p:PublishSingleFile=true ^
  -p:IncludeNativeLibrariesForSelfExtract=true ^
  -p:EnableCompressionInSingleFile=true ^
  -o "%~dp0Publish"

if %ERRORLEVEL% EQU 0 (
    echo.
    echo [SUCCESS] Portable native executable generated at:
    echo   %~dp0Publish\SanDen-Desktop.exe
) else (
    echo.
    echo [ERROR] Build failed with code %ERRORLEVEL%.
)
pause
