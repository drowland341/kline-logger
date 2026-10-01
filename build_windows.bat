@echo off
setlocal
cd /d "%~dp0"

echo === 1/3  Installing build tools ===
python -m pip install -r requirements.txt pyinstaller || goto :error

echo === 2/3  Building the app ===
python -m PyInstaller --noconfirm --clean KlineLogger.spec || goto :error

set "ISCC=%ProgramFiles(x86)%\Inno Setup 6\ISCC.exe"
if not exist "%ISCC%" set "ISCC=%ProgramFiles%\Inno Setup 6\ISCC.exe"
if not exist "%ISCC%" set "ISCC=%LOCALAPPDATA%\Programs\Inno Setup 6\ISCC.exe"
if not exist "%ISCC%" (
    echo.
    echo The app is built in dist\KlineLogger. To also make the installer,
    echo install Inno Setup 6 from https://jrsoftware.org/isdl.php and run this again.
    goto :end
)

echo === 3/3  Building the installer ===
"%ISCC%" installer.iss || goto :error
echo.
echo Done. The installer is in the "installer" folder.
goto :end

:error
echo.
echo Build failed. See the messages above.
:end
pause
