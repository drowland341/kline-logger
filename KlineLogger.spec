# PyInstaller recipe for the Windows app. Build with:  build_windows.bat
# (or:  python -m PyInstaller --noconfirm --clean KlineLogger.spec)
from PyInstaller.utils.hooks import collect_data_files

datas = collect_data_files("customtkinter")      # CustomTkinter's themes and fonts
datas += [("assets", "assets")]                   # icon and header logo

a = Analysis(
    ["kawasaki_logger.py"],
    datas=datas,
    hiddenimports=["serial.tools.list_ports", "serial.tools.list_ports_windows"],
    excludes=["matplotlib", "numpy", "pandas", "PIL"],
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="KlineLogger",
    icon="assets/icon.ico",
    console=False,          # no black console window behind the app
    upx=False,
)
coll = COLLECT(exe, a.binaries, a.datas, upx=False, name="KlineLogger")
