@echo off
cd /d "%~dp0"
where py >nul 2>nul
if %errorlevel%==0 (py -3 kawasaki_logger.py) else (python kawasaki_logger.py)
if errorlevel 1 pause
