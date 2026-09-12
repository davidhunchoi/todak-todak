@echo off
REM ============================================================
REM  토닥토닥 APK GitHub Release 배포 스크립트
REM  사용법: deploy.bat [v1.2.0]
REM  준비물: Python 3, 그리고 아래 중 하나
REM   방법1: gh CLI 설치 + `gh auth login`
REM   방법2: GITHUB_TOKEN 환경변수 (repo 권한)
REM ============================================================
set TAG=%~1
if "%TAG%"=="" set TAG=v1.2.0
python "%~dp0deploy.py" "%TAG%"
pause