@echo off
REM LQXM - package backend and restart dev backend (8081)
REM Double-click this file: it opens a fresh PowerShell window and runs scripts\rebuild-backend.ps1
REM (a fresh window avoids the IDE terminal issue described in docs, section 5.4).

cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -NoExit -File "%~dp0scripts\rebuild-backend.ps1"
