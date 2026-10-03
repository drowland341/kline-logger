#!/usr/bin/env python3
"""
K-line Logger for Kawasaki PWC ECUs - GUI.

Run:    python kawasaki_logger.py
Needs:  Python 3.9+, plus:  pip install pyserial customtkinter
Pick "Demo" as the cable to try everything without a ski.
"""
from __future__ import annotations

import csv
import math
import os
import queue
import re
import subprocess
import sys
import time
import traceback
from collections import deque
from datetime import datetime

import tkinter as tk
from tkinter import filedialog, messagebox, ttk

try:
    import customtkinter as ctk
except ImportError:
    ctk = None

import kline as kl

APP_NAME = "K-line Logger"
APP_VERSION = "1.1.0"
FROZEN = getattr(sys, "frozen", False)                # True when running as the installed .exe
APP_DIR = os.path.dirname(os.path.abspath(sys.executable if FROZEN else __file__))
BUNDLE_DIR = getattr(sys, "_MEIPASS", APP_DIR)        # where PyInstaller unpacks bundled files
ASSETS_DIR = os.path.join(BUNDLE_DIR, "assets")
ICON_PATH = os.path.join(ASSETS_DIR, "icon.ico")


def documents_dir() -> str:
    """The real Documents folder, including when OneDrive has moved it."""
    if sys.platform == "win32":
        try:
            import ctypes
            buf = ctypes.create_unicode_buffer(260)
            if ctypes.windll.shell32.SHGetFolderPathW(None, 5, None, 0, buf) == 0 and buf.value:
                return buf.value
        except Exception:
            pass
    return os.path.join(os.path.expanduser("~"), "Documents")


if FROZEN:
    # Installed: settings live in AppData, logs in Documents. Uninstalling never deletes either.
    DATA_DIR = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), APP_NAME)
    LOG_BASE = os.path.join(documents_dir(), APP_NAME)
else:
    # Running from source: keep everything next to the code, as before.
    DATA_DIR = LOG_BASE = APP_DIR
SETTINGS_PATH = os.path.join(DATA_DIR, "settings.json")
PIDS_PATH = os.path.join(DATA_DIR, "pids.json")

C = {
    "bg": "#E8EDF1",            # window background
    "panel": "#FFFFFF",         # cards
    "line": "#D3DCE3",          # card borders
    "ink": "#13304A",           # main text, numerals
    "muted": "#5E7487",         # secondary text
    "header": "#13304A",
    "header_muted": "#A9BCCB",
    "header_line": "#46678A",
    "pill": "#21456A",
    "pill_hover": "#2C5884",
    "accent": "#1F64B5",
    "accent_hover": "#2A74C8",
    "rec": "#C0392B",
    "rec_hover": "#D24A3C",
    "ok": "#2E8B57",
    "warn": "#A9741A",
    "bad": "#B23A3A",
    "dot_ok": "#3CC27A",
    "dot_warn": "#F2B84B",
    "dot_off": "#7F95A6",
    "stale": "#A3B1BD",
    "grid": "#EDF1F4",
    "track": "#E8EDF1",
    "chip": "#EEF2F5",
    "chip_hover": "#E0E7ED",
    "secondary_hover": "#F3F6F8",
    "badge_bg": "#FBF1DC",
    "tab": "#D5DDE4",
    "tab_hover": "#C8D2DB",
    "select": "#D6E6F7",
    "zebra": "#F7F9FB",
    "flash": "#FFF0B3",
}
SERIES = ["#1F64B5", "#D2641E", "#2E9E5B", "#8447A6", "#D4387A", "#0E8C8C", "#B8860B", "#5D6D7E"]
UI_FONT = "Segoe UI" if sys.platform == "win32" else "Helvetica"
MONO_FONT = "Consolas" if sys.platform == "win32" else "Courier"
PAGES = ["Live data", "Channels", "PID scanner", "Console", "Settings"]
ADVANCED_PAGES = {"PID scanner", "Console"}
CHART_WINDOWS = ["15 s", "30 s", "60 s"]
LOG_HINT = "Press Start logging to record a CSV. Press F2 during a pull to mark it in the log."


def list_ports():
    out = []
    if kl.serial_available():
        from serial.tools import list_ports as lp
        for p in sorted(lp.comports(), key=lambda p: p.device):
            desc = re.sub(r"\s*\(COM\d+\)$", "", p.description or "")    # Windows repeats the COM number
            out.append((p.device, f"{p.device}   {desc}".strip()))
    out.append((kl.DEMO_PORT, "Demo (no ski needed)"))
    return out


def fmt_value(v) -> str:
    if v is None:
        return "--"
    if float(v).is_integer() and abs(v) < 1e7:
        return str(int(v))
    a = abs(v)
    if a >= 100:
        return f"{v:.0f}"
    if a >= 10:
        return f"{v:.1f}"
    return f"{v:.2f}"


def tile_columns(n: int) -> int:
    """4 or 3 columns, whichever leaves fewer empty slots (4 on a tie)."""
    if n <= 4:
        return max(1, n)
    return min((4, 3), key=lambda c: ((-n) % c, -c))


def open_folder(path: str) -> None:
    os.makedirs(path, exist_ok=True)
    if sys.platform == "win32":
        os.startfile(path)  # noqa: S606 - opening the user's own log folder
    elif sys.platform == "darwin":
        subprocess.Popen(["open", path])
    else:
        subprocess.Popen(["xdg-open", path])


def gauge_image(size: int, background: str) -> tk.PhotoImage:
    """A small gauge (white arc, amber needle) used for the window icon and the header logo."""
    cx, cy = size / 2 - 0.5, size * 0.56
    ux, uy = math.cos(math.radians(50)), -math.sin(math.radians(50))
    r_in, r_out = size * 0.31, size * 0.40
    needle, thick, hub = size * 0.34, size * 0.04 + 0.8, size * 0.075
    rows = []
    for y in range(size):
        row = []
        for x in range(size):
            dx, dy = x - cx, y - cy
            d = math.hypot(dx, dy)
            ang = math.degrees(math.atan2(-dy, dx))
            t = dx * ux + dy * uy
            color = background
            if r_in <= d <= r_out and -25 <= ang <= 205:
                color = "#FFFFFF"
            if (0 <= t <= needle and abs(dx * uy - dy * ux) < thick) or d < hub:
                color = C["dot_warn"]
            row.append(color)
        rows.append("{" + " ".join(row) + "}")
    img = tk.PhotoImage(width=size, height=size)
    img.put(" ".join(rows))
    return img


class Tooltip:
    """Small hover hint. Explains icon-ish controls and jargon without cluttering the window."""

    def __init__(self, widget, text: str):
        self.widget, self.text = widget, text
        self.tip = None
        self.job = None
        widget.bind("<Enter>", self._enter, add="+")
        widget.bind("<Leave>", self._leave, add="+")
        widget.bind("<ButtonPress>", self._leave, add="+")

    def _enter(self, _event=None):
        self._cancel()
        self.job = self.widget.after(450, self._show)

    def _leave(self, _event=None):
        self._cancel()
        if self.tip is not None:
            self.tip.destroy()
            self.tip = None

    def _cancel(self):
        if self.job is not None:
            self.widget.after_cancel(self.job)
            self.job = None

    def _show(self):
        if self.tip is not None:
            return
        x = self.widget.winfo_rootx() + 8
        y = self.widget.winfo_rooty() + self.widget.winfo_height() + 6
        self.tip = tk.Toplevel(self.widget)
        self.tip.wm_overrideredirect(True)
        self.tip.wm_geometry(f"+{x}+{y}")
        tk.Label(self.tip, text=self.text, bg=C["ink"], fg="white", font=(UI_FONT, 9), padx=10, pady=6,
                 justify="left", wraplength=320).pack()


class App(ctk.CTk if ctk else object):
    def __init__(self):
        ctk.set_appearance_mode("light")
        ctk.set_default_color_theme("blue")
        super().__init__(fg_color=C["bg"])
        self.title(APP_NAME)
        os.makedirs(DATA_DIR, exist_ok=True)
        self.s = self._scaling()
        self._fit_to_screen()
        self._fonts: dict = {}

        self.settings = kl.load_settings(SETTINGS_PATH)
        self.temp_unit = "C" if str(self.settings.get("temp_unit", "F")).upper() == "C" else "F"
        self.last_values: dict = {}
        problems = []
        self.channels_revision = kl.DEFAULTS_REVISION
        try:
            self.pids, problems = kl.load_pids(PIDS_PATH)
            self.channels_revision = kl.pids_revision(PIDS_PATH)
        except (OSError, ValueError) as e:
            messagebox.showerror("Channel file problem",
                                 f"Could not read pids.json:\n{e}\n\nUsing the built-in channels instead.")
            self.pids = [p.copy() for p in kl.DEFAULT_PIDS]

        self.events: queue.Queue = queue.Queue()
        self.engine = kl.Engine(self.events)
        self.engine.start()

        self.link_state = "disconnected"
        self.logging_path = None
        self.log_started = 0.0
        self.log_rows = 0
        self._blink = False
        self.marker_count = 0
        self.chart_markers: deque = deque(maxlen=50)
        self.chart_seconds = int(self.settings.get("chart_seconds", 30))
        self.history: dict = {}
        self.charted: list[int] = []
        self.tiles: dict = {}
        self.last_raw: dict = {}
        self.scan_rows: dict = {}
        self.scan_total = 0
        self.scan_count = 0
        self.scan_sort = ("pid", False)
        self.chart_dirty = True
        self.ports: list = []
        self.pages: dict = {}
        self.current_page = "Live data"

        self._ttk_styles()
        self._icons()
        self._build()
        self._refresh_ports()
        self._rebuild_tiles()
        self._refresh_channel_list()
        self._apply_advanced(save=False)

        if problems:
            messagebox.showwarning("Some channels were skipped", "\n".join(problems))
        if not kl.serial_available():
            self._status("pyserial is not installed, so only the demo will work. Run: pip install pyserial", "warn")

        self.protocol("WM_DELETE_WINDOW", self._on_close)
        self.bind_all("<F2>", lambda e: self._add_marker())
        self.bind_all("<Control-l>", lambda e: self._toggle_logging())
        self.after(50, self._drain_events)
        self.after(200, self._chart_tick)
        self.after(500, self._log_tick)
        self.after(2000, self._port_watch)
        self.after(900, self._check_channel_defaults)

    # ------------------------------------------------------------ helpers
    def _scaling(self) -> float:
        try:
            return float(ctk.ScalingTracker.get_widget_scaling(self))
        except Exception:
            try:
                return max(1.0, float(self.winfo_fpixels("1i")) / 96.0)
            except Exception:
                return 1.0

    def _fit_to_screen(self):
        """1220x880 where there's room; smaller on laptops running Windows at 125-150% scaling."""
        try:
            screen_w = float(self.winfo_screenwidth()) / self.s
            screen_h = float(self.winfo_screenheight()) / self.s
            w = int(min(1220, screen_w * 0.94)) if screen_w > 0 else 1220
            h = int(min(880, screen_h * 0.88)) if screen_h > 0 else 880
        except (tk.TclError, TypeError, ZeroDivisionError):
            w, h = 1220, 880
        self.geometry(f"{w}x{h}")
        self.minsize(min(960, w), min(620, h))

    def px(self, n: float) -> int:
        """Size for plain tk/ttk/canvas drawing, which CustomTkinter doesn't scale for us."""
        return int(round(n * self.s))

    def f(self, size: int, weight: str = "normal", family: str | None = None):
        key = (size, weight, family)
        if key not in self._fonts:
            self._fonts[key] = ctk.CTkFont(family=family or UI_FONT, size=size, weight=weight)
        return self._fonts[key]

    def cf(self, size: int, weight: str = "normal"):
        """Canvas font in scaled pixels."""
        return (UI_FONT, -self.px(size * 1.33), weight)

    def _button(self, parent, text, command, kind="secondary", width=110, height=36, **kw):
        styles = {
            "primary": dict(fg_color=C["accent"], hover_color=C["accent_hover"], text_color="white",
                            border_width=0, font=self.f(13, "bold")),
            "secondary": dict(fg_color=C["panel"], hover_color=C["secondary_hover"], text_color=C["ink"],
                              border_width=1, border_color=C["line"], font=self.f(13)),
            "danger": dict(fg_color=C["panel"], hover_color="#FBEDEB", text_color=C["bad"],
                           border_width=1, border_color="#EBC9C4", font=self.f(13)),
        }
        opts = dict(styles[kind])
        opts.update(kw)
        return ctk.CTkButton(parent, text=text, command=command, width=width, height=height,
                             corner_radius=8, **opts)

    def _page_title(self, page, title: str, description: str):
        head = ctk.CTkFrame(page, fg_color="transparent")
        head.pack(fill="x", padx=6, pady=(0, 12))
        ctk.CTkLabel(head, text=title, font=self.f(20, "bold"), text_color=C["ink"], anchor="w").pack(anchor="w")
        ctk.CTkLabel(head, text=description, font=self.f(13), text_color=C["muted"], anchor="w",
                     justify="left", wraplength=1000).pack(anchor="w")
        return head

    def _card(self, parent, **kw):
        return ctk.CTkFrame(parent, corner_radius=12, fg_color=C["panel"], border_width=1,
                            border_color=C["line"], **kw)

    # -------------------------------------------------------------- look
    def _ttk_styles(self):
        st = ttk.Style(self)
        try:
            st.theme_use("clam")
        except tk.TclError:
            pass
        st.configure("Clean.Treeview", background=C["panel"], fieldbackground=C["panel"], foreground=C["ink"],
                     rowheight=self.px(30), borderwidth=0, relief="flat", font=(UI_FONT, -self.px(13)))
        st.configure("Clean.Treeview.Heading", background=C["panel"], foreground=C["muted"], relief="flat",
                     borderwidth=0, lightcolor=C["panel"], darkcolor=C["line"], bordercolor=C["panel"],
                     font=(UI_FONT, -self.px(13), "bold"), padding=(self.px(6), self.px(7)))
        st.map("Clean.Treeview.Heading", background=[("active", C["chip"])])
        st.map("Clean.Treeview", background=[("selected", C["select"])], foreground=[("selected", C["ink"])])
        st.layout("Clean.Treeview", [("Clean.Treeview.treearea", {"sticky": "nswe"})])

    def _icons(self):
        self._logo = None
        try:
            want = self.px(30)
            sizes = [n for n in (24, 28, 32, 36, 40, 48, 56, 64)
                     if os.path.exists(os.path.join(ASSETS_DIR, f"logo_{n}.png"))]
            if sizes:
                best = min(sizes, key=lambda n: abs(n - want))
                self._logo = tk.PhotoImage(file=os.path.join(ASSETS_DIR, f"logo_{best}.png"))
            else:
                self._logo = gauge_image(want, C["header"])
            if sys.platform == "win32" and os.path.exists(ICON_PATH):
                self.iconbitmap(ICON_PATH)            # also stops CustomTkinter swapping in its own icon
            else:
                self._window_icon = gauge_image(32, C["header"])
                self.iconphoto(True, self._window_icon)
        except tk.TclError:
            pass

    # ------------------------------------------------------------ layout
    def _build(self):
        self._build_header()
        nav = ctk.CTkFrame(self, fg_color="transparent")
        nav.pack(fill="x", padx=20, pady=(16, 14))
        self.nav = ctk.CTkSegmentedButton(
            nav, values=PAGES, command=self._show_page, height=38, corner_radius=10,
            font=self.f(13, "bold"), fg_color=C["tab"], selected_color=C["panel"],
            selected_hover_color=C["panel"], unselected_color=C["tab"], unselected_hover_color=C["tab_hover"],
            text_color=C["ink"])
        self.nav.pack(side="left")

        self.page_box = ctk.CTkFrame(self, fg_color="transparent")
        self.page_box.pack(fill="both", expand=True, padx=14)
        self.pages["Live data"] = self._build_live(self.page_box)
        self.pages["Channels"] = self._build_channels(self.page_box)
        self.pages["PID scanner"] = self._build_scanner(self.page_box)
        self.pages["Console"] = self._build_console(self.page_box)
        self.pages["Settings"] = self._build_settings(self.page_box)

        self.status_var = tk.StringVar(value="Pick a cable and press Connect. Demo works without a ski.")
        self.status_lbl = ctk.CTkLabel(self, textvariable=self.status_var, font=self.f(12),
                                       text_color=C["muted"], anchor="w")
        self.status_lbl.pack(fill="x", padx=22, pady=(8, 10))
        self._show_page("Live data")

    def _show_page(self, name: str):
        for page in self.pages.values():
            page.pack_forget()
        self.pages[name].pack(fill="both", expand=True)
        self.current_page = name
        self.nav.set(name)
        if name == "Live data":
            self._mark_chart()

    def _build_header(self):
        bar = ctk.CTkFrame(self, corner_radius=0, fg_color=C["header"])
        bar.pack(fill="x")
        inner = ctk.CTkFrame(bar, fg_color="transparent")
        inner.pack(fill="x", padx=20, pady=12)
        if self._logo is not None:
            tk.Label(inner, image=self._logo, bg=C["header"], bd=0).pack(side="left", padx=(0, self.px(10)))
        ctk.CTkLabel(inner, text="K-line Logger", font=self.f(20, "bold"), text_color="white").pack(side="left")
        ctk.CTkLabel(inner, text="Kawasaki PWC live data", font=self.f(13),
                     text_color=C["header_muted"]).pack(side="left", padx=(12, 0), pady=(4, 0))

        pill = ctk.CTkFrame(inner, corner_radius=17, fg_color=C["pill"])
        pill.pack(side="right")
        self.state_dot = ctk.CTkLabel(pill, text="●", font=self.f(15), text_color=C["dot_off"], width=14)
        self.state_dot.pack(side="left", padx=(14, 6), pady=3)
        self.state_lbl = ctk.CTkLabel(pill, text="Not connected", font=self.f(13, "bold"), text_color="white",
                                      width=126, anchor="w")
        self.state_lbl.pack(side="left", padx=(0, 14), pady=3)

        self.connect_btn = ctk.CTkButton(inner, text="Connect", width=120, height=36, corner_radius=8,
                                         font=self.f(13, "bold"), command=self._toggle_connect)
        self._style_connect(False)
        self.connect_btn.pack(side="right", padx=(0, 14))
        Tooltip(self.connect_btn, "Wake the ECU and start reading live data")

        refresh = ctk.CTkButton(inner, text="Refresh", width=84, height=36, corner_radius=8,
                                fg_color="transparent", hover_color=C["pill"], border_width=1,
                                border_color=C["header_line"], text_color=C["header_muted"],
                                font=self.f(13), command=self._refresh_ports)
        refresh.pack(side="right", padx=(0, 10))
        Tooltip(refresh, "Look again for plugged-in cables")
        self.port_var = tk.StringVar()
        self.port_menu = ctk.CTkOptionMenu(
            inner, variable=self.port_var, values=["Demo (no ski needed)"], width=250, height=36,
            corner_radius=8, fg_color=C["pill"], button_color=C["pill_hover"], button_hover_color="#356699",
            text_color="white", dropdown_fg_color=C["panel"], dropdown_hover_color=C["chip"],
            dropdown_text_color=C["ink"], font=self.f(13), dropdown_font=self.f(13), dynamic_resizing=False)
        self.port_menu.pack(side="right", padx=(0, 8))
        ctk.CTkLabel(inner, text="Cable", font=self.f(13), text_color=C["header_muted"]).pack(
            side="right", padx=(0, 10))

    def _style_connect(self, connected: bool):
        if connected:
            self.connect_btn.configure(text="Disconnect", fg_color="transparent", hover_color=C["pill"],
                                       border_width=1, border_color=C["header_line"], text_color="white")
        else:
            self.connect_btn.configure(text="Connect", fg_color=C["accent"], hover_color=C["accent_hover"],
                                       border_width=0, text_color="white")

    # --- Live data page
    def _build_live(self, parent):
        page = ctk.CTkFrame(parent, fg_color="transparent")
        bar = ctk.CTkFrame(page, fg_color="transparent")
        bar.pack(fill="x", padx=6)
        self.log_btn = self._button(bar, "Start logging", self._toggle_logging, "primary", width=150)
        self.log_btn.pack(side="left")
        Tooltip(self.log_btn, "Record every reading to a CSV file  (Ctrl+L)")
        ctk.CTkLabel(bar, text="Run name", font=self.f(13), text_color=C["muted"]).pack(side="left", padx=(18, 8))
        self.run_var = tk.StringVar(value=str(self.settings.get("run_name", "ski")))
        ctk.CTkEntry(bar, textvariable=self.run_var, width=170, height=36, corner_radius=8, border_width=1,
                     border_color=C["line"], fg_color=C["panel"], text_color=C["ink"],
                     font=self.f(13)).pack(side="left")
        marker = self._button(bar, "Add marker", self._add_marker, width=110)
        marker.pack(side="left", padx=(10, 0))
        Tooltip(marker, "Mark this moment in the log, for example the start of a WOT pull  (F2)")
        self._button(bar, "Open logs folder", lambda: open_folder(self._log_dir()), width=130).pack(
            side="left", padx=(8, 0))
        self.rate_lbl = ctk.CTkLabel(bar, text="", font=self.f(12), text_color=C["muted"],
                                     fg_color="transparent", corner_radius=13, height=26)
        self.rate_lbl.pack(side="right")
        self.temp_seg = ctk.CTkSegmentedButton(
            bar, values=["\u00b0F", "\u00b0C"], command=self._set_temp_unit, width=96, height=32,
            corner_radius=8, font=self.f(12, "bold"), fg_color=C["chip"], selected_color=C["accent"],
            selected_hover_color=C["accent_hover"], unselected_color=C["chip"],
            unselected_hover_color=C["chip_hover"], text_color=C["ink"])
        self.temp_seg.pack(side="right", padx=(0, 10))
        self.temp_seg.set(f"\u00b0{self.temp_unit}")
        # Segmented buttons don't allow event bindings, so the hover hint goes on a label beside it.
        temp_lbl = ctk.CTkLabel(bar, text="Temp", font=self.f(12), text_color=C["muted"])
        temp_lbl.pack(side="right", padx=(0, 6))
        Tooltip(temp_lbl, "Show temperatures in \u00b0F or \u00b0C. Locked while a log is recording, "
                          "so one log file never mixes units.")

        self.log_lbl = ctk.CTkLabel(page, text=LOG_HINT, font=self.f(12), text_color=C["muted"], anchor="w")
        self.log_lbl.pack(fill="x", padx=6, pady=(8, 2))

        self.tile_grid = ctk.CTkFrame(page, fg_color="transparent")
        self.tile_grid.pack(fill="x")

        panel = self._card(page)
        panel.pack(fill="both", expand=True, padx=6, pady=(8, 0))
        head = ctk.CTkFrame(panel, fg_color="transparent")
        head.pack(fill="x", padx=16, pady=(12, 4))
        ctk.CTkLabel(head, text="Chart", font=self.f(15, "bold"), text_color=C["ink"]).pack(side="left")
        ctk.CTkLabel(head, text="Each channel gets its own lane. Add channels with the Chart button on a tile.",
                     font=self.f(12), text_color=C["muted"]).pack(side="left", padx=(12, 0))
        self.window_seg = ctk.CTkSegmentedButton(
            head, values=CHART_WINDOWS, command=self._set_chart_window, height=30, corner_radius=8,
            font=self.f(12), fg_color=C["chip"], selected_color=C["accent"], selected_hover_color=C["accent_hover"],
            unselected_color=C["chip"], unselected_hover_color=C["chip_hover"], text_color=C["ink"])
        self.window_seg.pack(side="right")
        self.window_seg.set(f"{self.chart_seconds} s" if f"{self.chart_seconds} s" in CHART_WINDOWS else "30 s")
        self._button(head, "Clear", self._chart_none, width=70, height=30).pack(side="right", padx=(0, 10))
        self._button(head, "Chart all", self._chart_all, width=90, height=30).pack(side="right", padx=(0, 6))

        self.chart = tk.Canvas(panel, bg=C["panel"], highlightthickness=0, height=self.px(260))
        self.chart.pack(fill="both", expand=True, padx=self.px(14), pady=(self.px(4), self.px(14)))
        self.chart.bind("<Configure>", lambda e: self._mark_chart())
        return page

    def _rebuild_tiles(self):
        for w in self.tile_grid.winfo_children():
            w.destroy()
        self.tiles = {}
        enabled = [i for i, p in enumerate(self.pids) if p.enabled]
        names = list(self.settings.get("charted", []))
        by_name = {self.pids[i].name: i for i in enabled}
        self.charted = [by_name[n] for n in names if n in by_name]
        for c in range(4):
            self.tile_grid.grid_columnconfigure(c, weight=0, uniform="")
        if not enabled:
            ctk.CTkLabel(self.tile_grid, text="No channels are switched on. Turn some on in Channels.",
                         font=self.f(13), text_color=C["muted"]).grid(row=0, column=0, padx=6, pady=6, sticky="w")
            return
        cols = tile_columns(len(enabled))
        for c in range(cols):
            self.tile_grid.grid_columnconfigure(c, weight=1, uniform="tile")
        for n, i in enumerate(enabled):
            self._make_tile(i, n // cols, n % cols)
        self._paint_tiles()
        self._mark_chart()

    def _make_tile(self, i: int, row: int, col: int):
        p = self.pids[i]
        frame = ctk.CTkFrame(self.tile_grid, corner_radius=14, fg_color=C["panel"], border_width=2,
                             border_color=C["line"], cursor="hand2")
        frame.grid(row=row, column=col, sticky="nsew", padx=6, pady=6)
        head = ctk.CTkFrame(frame, fg_color="transparent", cursor="hand2")
        head.pack(fill="x", padx=16, pady=(12, 0))
        name = ctk.CTkLabel(head, text=p.name, font=self.f(13, "bold"), text_color=C["muted"], anchor="w",
                            height=26, cursor="hand2")
        name.pack(side="left")
        chart_btn = ctk.CTkButton(head, text="Chart", width=74, height=26, corner_radius=13,
                                  font=self.f(12, "bold"), command=lambda i=i: self._toggle_chart(i))
        chart_btn.pack(side="right")
        if not p.verified:
            badge = ctk.CTkLabel(head, text="unverified", font=self.f(11), text_color=C["warn"],
                                 fg_color=C["badge_bg"], corner_radius=10, height=22)
            badge.pack(side="right", padx=(0, 8))
            Tooltip(badge, "This channel's scaling hasn't been checked against a known-good reading yet. "
                           "Tick 'Scaling checked' in Channels once it has.")
        value_row = ctk.CTkFrame(frame, fg_color="transparent", cursor="hand2")
        value_row.pack(fill="x", padx=16, pady=(2, 0))
        val = ctk.CTkLabel(value_row, text="--", font=self.f(32, "bold"), text_color=C["stale"], height=46,
                           cursor="hand2")
        val.pack(side="left")
        unit = ctk.CTkLabel(value_row, text=self._unit_text(p), font=self.f(14),
                            text_color=C["muted"], cursor="hand2")
        unit.pack(side="left", padx=(8, 0), pady=(10, 0))
        bar = ctk.CTkProgressBar(frame, height=8, corner_radius=4, fg_color=C["track"],
                                 progress_color=C["accent"])
        bar.set(0)
        bar.pack(fill="x", padx=16, pady=(6, 0))
        sub = ctk.CTkLabel(frame, text="Waiting for data", font=self.f(12), text_color=C["muted"], anchor="w",
                           height=22, cursor="hand2")
        sub.pack(fill="x", padx=16, pady=(6, 12))
        for w in (frame, head, name, value_row, val, unit, sub):
            w.bind("<Button-1>", lambda e, i=i: self._toggle_chart(i), add="+")
        self.tiles[i] = {"frame": frame, "val": val, "sub": sub, "bar": bar, "chart_btn": chart_btn,
                         "unit": unit, "range": [None, None], "cache": {}}

    def _set(self, tile: dict, key: str, **kw):
        """Configure a tile widget only when something changed; CustomTkinter redraws on every configure."""
        if tile["cache"].get(key) != kw:
            tile["cache"][key] = kw
            tile[key].configure(**kw)

    def _color(self, i: int) -> str:
        return SERIES[i % len(SERIES)]

    def _paint_tiles(self):
        for i, t in self.tiles.items():
            on = i in self.charted
            col = self._color(i)
            self._set(t, "frame", border_color=col if on else C["line"])
            self._set(t, "chart_btn", text="Charted" if on else "Chart",
                      fg_color=col if on else C["chip"], hover_color=col if on else C["chip_hover"],
                      text_color="white" if on else C["muted"])
            self._set(t, "bar", progress_color=col if on else C["accent"])

    def _save_charted(self):
        self.settings["charted"] = [self.pids[i].name for i in self.charted]
        self._write_settings()

    def _toggle_chart(self, i: int):
        if i in self.charted:
            self.charted.remove(i)
        else:
            self.charted.append(i)
        self._save_charted()
        self._paint_tiles()
        self._mark_chart()

    def _chart_all(self):
        self.charted = [i for i, p in enumerate(self.pids) if p.enabled]
        self._save_charted()
        self._paint_tiles()
        self._mark_chart()

    def _chart_none(self):
        self.charted = []
        self._save_charted()
        self._paint_tiles()
        self._mark_chart()

    def _set_chart_window(self, value: str):
        self.chart_seconds = int(value.split()[0])
        self.settings["chart_seconds"] = self.chart_seconds
        self._write_settings()
        self._mark_chart()

    # --- shared table card
    def _tree_card(self, parent, cols, heads, widths, selectmode):
        card = self._card(parent)
        inner = tk.Frame(card, bg=C["panel"])
        inner.pack(fill="both", expand=True, padx=self.px(12), pady=self.px(10))
        tree = ttk.Treeview(inner, columns=cols, show="headings", selectmode=selectmode, style="Clean.Treeview")
        for c, h, w in zip(cols, heads, widths):
            tree.heading(c, text=h, anchor="w")
            tree.column(c, width=self.px(w), anchor="w")
        sb = ctk.CTkScrollbar(inner, command=tree.yview)
        tree.configure(yscrollcommand=sb.set)
        tree.pack(side="left", fill="both", expand=True)
        sb.pack(side="left", fill="y")
        tree.tag_configure("odd", background=C["zebra"])
        tree.tag_configure("changed", background=C["flash"])
        tree.tag_configure("dim", foreground=C["muted"])
        return card, tree

    # --- Channels page
    def _build_channels(self, parent):
        page = ctk.CTkFrame(parent, fg_color="transparent")
        self._page_title(page, "Channels", "Each channel reads one PID and turns its bytes into a number. "
                                           "Double-click a row to edit it.")
        cols = ("on", "name", "pid", "formula", "units", "every", "gauge", "checked")
        heads = ("On", "Name", "PID", "Formula", "Units", "Read", "Gauge", "Scaling checked")
        widths = (50, 190, 60, 250, 70, 150, 110, 120)
        card, self.ch_tree = self._tree_card(page, cols, heads, widths, "browse")
        card.pack(fill="both", expand=True, padx=6)
        self.ch_tree.bind("<Double-1>", lambda e: self._edit_channel())

        btns = ctk.CTkFrame(page, fg_color="transparent")
        btns.pack(fill="x", padx=6, pady=(12, 0))
        self._button(btns, "Add channel", self._add_channel, "primary", width=120).pack(side="left", padx=(0, 8))
        for text, cmd, w in (("Edit", self._edit_channel, 80), ("Turn on/off", self._toggle_channel, 110),
                             ("Move up", lambda: self._move_channel(-1), 90),
                             ("Move down", lambda: self._move_channel(1), 100)):
            self._button(btns, text, cmd, width=w).pack(side="left", padx=(0, 8))
        self._button(btns, "Remove", self._remove_channel, "danger", width=90).pack(side="left")
        self._button(btns, "Reload pids.json", self._reload_channels, width=140).pack(side="right")
        reset_btn = self._button(btns, "Reset to defaults", self._reset_channels, "danger", width=150)
        reset_btn.pack(side="right", padx=(0, 8))
        Tooltip(reset_btn, "Replace the whole channel list with the built-in defaults. Use this after updating "
                           "the app so you get the newest scalings.")
        return page

    def _refresh_channel_list(self):
        t = self.ch_tree
        t.delete(*t.get_children())
        for i, p in enumerate(self.pids):
            every = "every update" if p.every == 1 else f"every {p.every} updates"
            if p.gauge_min is None and p.gauge_max is None:
                gauge = "auto"
            else:
                lo = fmt_value(p.gauge_min) if p.gauge_min is not None else "auto"
                hi = fmt_value(p.gauge_max) if p.gauge_max is not None else "auto"
                gauge = f"{lo} to {hi}"
            t.insert("", "end", iid=str(i), tags=("odd",) if i % 2 else (),
                     values=("yes" if p.enabled else "no", p.name, f"0x{p.pid:02X}", p.formula, p.units, every,
                             gauge, "yes" if p.verified else "no"))

    # --- PID scanner page
    def _build_scanner(self, parent):
        page = ctk.CTkFrame(parent, fg_color="transparent")
        self._page_title(page, "PID scanner",
                         "Scan asks the ECU for every PID in the range. To find a sensor: select a few rows (or none "
                         "for all), press Watch, change what that sensor measures, then click the Swing heading to put "
                         "the biggest movers first. Add the one you want as a channel to name and scale it.")
        bar = ctk.CTkFrame(page, fg_color="transparent")
        bar.pack(fill="x", padx=6)
        ctk.CTkLabel(bar, text="From", font=self.f(13), text_color=C["muted"]).pack(side="left", padx=(0, 6))
        self.scan_from = tk.StringVar(value="0x00")
        self.scan_to = tk.StringVar(value="0xFF")
        for var, pad in ((self.scan_from, (0, 10)), (self.scan_to, (0, 14))):
            ctk.CTkEntry(bar, textvariable=var, width=70, height=36, corner_radius=8, border_width=1,
                         border_color=C["line"], fg_color=C["panel"], text_color=C["ink"],
                         font=self.f(13)).pack(side="left", padx=pad)
            if var is self.scan_from:
                ctk.CTkLabel(bar, text="to", font=self.f(13), text_color=C["muted"]).pack(side="left", padx=(0, 6))
        self._button(bar, "Scan", self._scan, "primary", width=90).pack(side="left")
        watch_btn = self._button(bar, "Watch", self._watch, width=90)
        watch_btn.pack(side="left", padx=(8, 0))
        Tooltip(watch_btn, "Re-read the selected rows over and over (or every PID that answered, if none are "
                           "selected) and track how far each one moves. Fewer rows means faster readings.")
        self._button(bar, "Stop", lambda: self.engine.send("stop_scan"), width=80).pack(side="left", padx=(8, 0))
        self._button(bar, "Save results", self._save_scan, width=120).pack(side="right")
        self._button(bar, "Add selected as channels", self._scan_to_channels, width=200).pack(
            side="right", padx=(0, 8))

        prog = ctk.CTkFrame(page, fg_color="transparent")
        prog.pack(fill="x", padx=6, pady=(10, 8))
        self.scan_prog = tk.StringVar(value="No scan yet.")
        ctk.CTkLabel(prog, textvariable=self.scan_prog, font=self.f(12), text_color=C["muted"]).pack(side="left")
        self.scan_show_all = tk.BooleanVar(value=False)
        ctk.CTkSwitch(prog, text="Show PIDs that didn't answer", variable=self.scan_show_all, onvalue=True,
                      offvalue=False, command=self._refill_scan_tree, font=self.f(12), text_color=C["ink"],
                      progress_color=C["accent"]).pack(side="right")

        cols = ("pid", "result", "bytes", "data", "value", "low", "high", "swing", "changes")
        heads = ("PID", "Result", "Bytes", "Data (hex)", "Value", "Low", "High", "Swing", "Changes")
        widths = (70, 170, 60, 150, 90, 90, 90, 80, 90)
        card, self.scan_tree = self._tree_card(page, cols, heads, widths, "extended")
        for c, h in zip(cols, heads):              # click a heading to sort by it
            self.scan_tree.heading(c, text=h, anchor="w", command=lambda c=c: self._sort_scan(c))
        card.pack(fill="both", expand=True, padx=6)
        return page

    # --- Console page
    def _build_console(self, parent):
        page = ctk.CTkFrame(parent, fg_color="transparent")
        self._page_title(page, "Console", "Send any request in hex and watch every frame on the bus. "
                                          "The header and checksum are added for you. Example: 21 09 reads PID 0x09.")
        bar = ctk.CTkFrame(page, fg_color="transparent")
        bar.pack(fill="x", padx=6)
        self.console_var = tk.StringVar()
        entry = ctk.CTkEntry(bar, textvariable=self.console_var, width=280, height=36, corner_radius=8,
                             border_width=1, border_color=C["line"], fg_color=C["panel"], text_color=C["ink"],
                             font=self.f(14, family=MONO_FONT))
        entry.pack(side="left")
        entry.bind("<Return>", lambda e: self._console_send())
        self._button(bar, "Send", self._console_send, "primary", width=80).pack(side="left", padx=(8, 0))
        self._button(bar, "Clear", self._console_clear, width=80).pack(side="right")
        self.save_traffic = tk.BooleanVar(value=False)
        ctk.CTkSwitch(bar, text="Save bus traffic to a file", variable=self.save_traffic, onvalue=True,
                      offvalue=False, command=self._toggle_traffic_file, font=self.f(12), text_color=C["ink"],
                      progress_color=C["accent"]).pack(side="right", padx=(0, 16))
        self.show_traffic = tk.BooleanVar(value=True)
        ctk.CTkSwitch(bar, text="Show bus traffic", variable=self.show_traffic, onvalue=True, offvalue=False,
                      font=self.f(12), text_color=C["ink"], progress_color=C["accent"]).pack(side="right", padx=(0, 16))

        card = self._card(page)
        card.pack(fill="both", expand=True, padx=6, pady=(12, 0))
        inner = tk.Frame(card, bg=C["panel"])
        inner.pack(fill="both", expand=True, padx=self.px(12), pady=self.px(10))
        self.console_text = tk.Text(inner, font=(MONO_FONT, -self.px(13)), bg=C["panel"], fg=C["ink"],
                                    relief="flat", bd=0, highlightthickness=0, wrap="none", state="disabled",
                                    padx=self.px(4), pady=self.px(4))
        sb = ctk.CTkScrollbar(inner, command=self.console_text.yview)
        self.console_text.configure(yscrollcommand=sb.set)
        self.console_text.pack(side="left", fill="both", expand=True)
        sb.pack(side="left", fill="y")
        self.console_text.tag_configure("tx", foreground=SERIES[0])
        self.console_text.tag_configure("rx", foreground=C["ink"])
        self.console_text.tag_configure("note", foreground=C["muted"])
        self.console_text.tag_configure("bad", foreground=C["bad"])
        return page

    # --- Settings page
    def _build_settings(self, parent):
        page = ctk.CTkFrame(parent, fg_color="transparent")
        self._page_title(page, "Settings", "Connection and logging options. Pressing Connect saves them too.")
        scroll = ctk.CTkScrollableFrame(page, fg_color="transparent", scrollbar_button_color=C["tab"],
                                        scrollbar_button_hover_color=C["tab_hover"])
        scroll.pack(fill="both", expand=True, padx=(6, 0))
        self.set_vars: dict = {}

        def card(title):
            frame = self._card(scroll)
            frame.pack(fill="x", padx=(0, 10), pady=(0, 12))
            frame.grid_columnconfigure(2, weight=1)
            ctk.CTkLabel(frame, text=title, font=self.f(15, "bold"), text_color=C["ink"], anchor="w").grid(
                row=0, column=0, columnspan=3, sticky="w", padx=18, pady=(14, 4))
            return [frame, 1]

        def field(cd, key, label, hint, kind="entry", choices=None):
            frame, r = cd
            ctk.CTkLabel(frame, text=label, font=self.f(13), text_color=C["ink"], anchor="w").grid(
                row=r, column=0, sticky="w", padx=(18, 14), pady=6)
            var = tk.StringVar(value=str(self.settings.get(key, "")))
            common = dict(width=220, height=34, corner_radius=8, font=self.f(13))
            if kind == "menu":
                w = ctk.CTkOptionMenu(frame, values=choices, variable=var, fg_color=C["chip"],
                                      button_color=C["chip_hover"], button_hover_color=C["tab"],
                                      text_color=C["ink"], dropdown_fg_color=C["panel"],
                                      dropdown_hover_color=C["chip"], dropdown_text_color=C["ink"],
                                      dropdown_font=self.f(13), dynamic_resizing=False, **common)
            elif kind == "combo":
                w = ctk.CTkComboBox(frame, values=choices, variable=var, border_width=1, border_color=C["line"],
                                    fg_color=C["panel"], button_color=C["chip_hover"], button_hover_color=C["tab"],
                                    text_color=C["ink"], dropdown_fg_color=C["panel"],
                                    dropdown_hover_color=C["chip"], dropdown_text_color=C["ink"],
                                    dropdown_font=self.f(13), **common)
            else:
                w = ctk.CTkEntry(frame, textvariable=var, border_width=1, border_color=C["line"],
                                 fg_color=C["panel"], text_color=C["ink"], **common)
            w.grid(row=r, column=1, sticky="w", pady=6)
            ctk.CTkLabel(frame, text=hint, font=self.f(12), text_color=C["muted"], anchor="w", justify="left",
                         wraplength=460).grid(row=r, column=2, sticky="w", padx=(16, 18), pady=6)
            self.set_vars[key] = var
            cd[1] += 1

        def switch(cd, key, label, command=None):
            frame, r = cd
            var = tk.BooleanVar(value=bool(self.settings.get(key, False)))
            ctk.CTkSwitch(frame, text=label, variable=var, onvalue=True, offvalue=False, command=command,
                          font=self.f(13), text_color=C["ink"], progress_color=C["accent"]).grid(
                row=r, column=1, columnspan=2, sticky="w", pady=6)
            self.set_vars[key] = var
            cd[1] += 1

        def end(cd):
            ctk.CTkFrame(cd[0], fg_color="transparent", height=8).grid(row=cd[1], column=0)

        cd = card("Connection")
        field(cd, "ecu_addr", "ECU address", "Auto tries 0x11, then 0x28. You can also type a hex address.",
              "combo", ["auto", "0x11", "0x28"])
        field(cd, "init_method", "Wake-up method",
              "break holds the line low for 25 ms. Try 360baud if a cable can't do a clean break.",
              "menu", ["break", "360baud"])
        field(cd, "baud", "Baud rate", "10400 for the handshake and normal live data.")
        field(cd, "tester_addr", "Tester address (hex)", "0xF1 is the standard tester address.")
        field(cd, "diag_session", "Diagnostic session (hex)", "Kawasaki ECUs want 0x80. Leave blank to skip.")
        switch(cd, "dtr", "Hold DTR on (some cables take power from it)")
        switch(cd, "rts", "Hold RTS on")
        end(cd)

        cd = card("Timing")
        field(cd, "request_gap_ms", "Gap between requests (ms)",
              "Kawasaki ECUs ignore requests that arrive faster than about 50 ms.")
        field(cd, "response_timeout_ms", "Reply timeout (ms)", "How long to wait for each answer.")
        end(cd)

        cd = card("High-speed mode")
        field(cd, "fast_switch_cmd", "Switch request (hex)",
              "Request that tells the ECU to change baud. Blank means stay at the baud rate above.")
        field(cd, "fast_baud", "High-speed baud",
              "Used only after the switch request. 62500 was seen during the SXR160 ROM read.")
        end(cd)

        cd = card("Logs and display")
        field(cd, "log_dir", "Log folder", f"Relative paths are inside {LOG_BASE}")
        switch(cd, "log_raw_columns", "Add raw byte columns to log files")
        switch(cd, "advanced", "Show advanced tools: PID scanner, console and raw bytes on tiles",
               command=self._apply_advanced)
        end(cd)

        btns = ctk.CTkFrame(scroll, fg_color="transparent")
        btns.pack(fill="x", pady=(4, 12))
        self._button(btns, "Save settings", self._save_settings, "primary", width=140).pack(side="left")
        ctk.CTkLabel(scroll, text=f"{APP_NAME} {APP_VERSION}.  Settings and channels are stored in {DATA_DIR}",
                     font=self.f(12), text_color=C["muted"], anchor="w").pack(fill="x", pady=(0, 12))
        return page

    def _apply_advanced(self, save=True):
        adv = bool(self.set_vars["advanced"].get())
        visible = [p for p in PAGES if adv or p not in ADVANCED_PAGES]
        self.nav.configure(values=visible)
        self._show_page(self.current_page if self.current_page in visible else "Live data")
        self.settings["advanced"] = adv
        if save:
            self._write_settings()
            self._status("Advanced tools shown." if adv else "Advanced tools hidden.", "info")

    # ------------------------------------------------------------ connection
    def _refresh_ports(self, announce=True):
        self._apply_ports(list_ports())
        if announce:
            real = [label for dev, label in self.ports if dev != kl.DEMO_PORT]
            if real:
                self._status(f"Found {len(real)} cable(s): {', '.join(real)}", "ok")
            else:
                self._status("No cable found. Plug in the USB K-line cable and it will appear in the list "
                             "on its own. Demo works without one.", "warn")

    def _apply_ports(self, ports, prefer=None):
        current = self._selected_port() if self.ports else None
        self.ports = ports
        labels = [label for _, label in ports]
        devices = [dev for dev, _ in ports]
        self.port_menu.configure(values=labels)
        saved = self.settings.get("port", "")
        pick = next((d for d in (prefer, current, saved) if d in devices), devices[0])
        self.port_var.set(labels[devices.index(pick)])

    def _port_watch(self):
        """Check for cables every 2 s while disconnected, so plugging one in just works."""
        if self.link_state == "disconnected":
            try:
                ports = list_ports()
            except Exception:
                ports = self.ports
            if ports != self.ports:
                before = {dev for dev, _ in self.ports}
                after = {dev for dev, _ in ports}
                added = [dev for dev, _ in ports if dev not in before and dev != kl.DEMO_PORT]
                removed = before - after
                self._apply_ports(ports, prefer=added[0] if added else None)
                if added:
                    label = dict(ports)[added[0]]
                    self._status(f"Cable found: {label}. Press Connect when the ECU is awake.", "ok")
                elif removed:
                    self._status("Cable unplugged.", "warn")
        self.after(2000, self._port_watch)

    def _selected_port(self):
        label = self.port_var.get()
        for dev, lab in self.ports:
            if lab == label:
                return dev
        return None

    def _toggle_connect(self):
        if self.link_state != "disconnected":
            self.engine.send("disconnect")
            return
        port = self._selected_port()
        if not port:
            messagebox.showinfo("Pick a cable", "Choose the cable's port, or Demo to try the app without a ski.")
            return
        if not self._save_settings(quiet=True):
            self._show_page("Settings")
            return
        self.settings.update(port=port, run_name=self.run_var.get().strip() or "ski")
        self._write_settings()
        for t in self.tiles.values():
            t["range"] = [None, None]
        self.history.clear()
        self.last_values.clear()
        self.chart_markers.clear()
        self.engine.send("connect", dict(self.settings), [p.copy() for p in self.pids])
        self._set_state("connecting", "Opening the port...")

    def _set_state(self, state: str, msg: str = ""):
        self.link_state = state
        label, dot = {
            "disconnected": ("Not connected", C["dot_off"]),
            "connecting": ("Connecting", C["dot_warn"]),
            "waiting": ("Waiting for ECU", C["dot_warn"]),
            "connected": ("Connected", C["dot_ok"]),
        }[state]
        self.state_lbl.configure(text=label)
        self.state_dot.configure(text_color=dot)
        self._style_connect(state != "disconnected")
        if state != "connected":
            for t in self.tiles.values():
                self._set(t, "val", text_color=C["stale"])    # grey out values that are no longer live
        if msg:
            self._status(msg, "warn" if state == "waiting" else "info")

    def _on_state(self, state: str, msg: str):
        self._set_state(state, msg)
        if state == "disconnected" and msg != "Disconnected.":
            self._status(msg, "bad")
            messagebox.showerror("Could not connect", msg)
        if state != "connected":
            self.rate_lbl.configure(text="", fg_color="transparent")

    # --------------------------------------------------------------- logging
    def _log_dir(self) -> str:
        d = str(self.settings.get("log_dir") or "logs")
        return d if os.path.isabs(d) else os.path.join(LOG_BASE, d)

    def _toggle_logging(self):
        if self.logging_path:
            self.engine.send("stop_log")
            return
        if self.link_state != "connected":
            messagebox.showinfo("Not connected", "Connect to the ECU first, then start logging.")
            return
        name = re.sub(r"[^A-Za-z0-9_-]+", "-", self.run_var.get().strip()).strip("-") or "ski"
        self.settings["run_name"] = self.run_var.get().strip()
        self._write_settings()
        path = os.path.join(self._log_dir(), f"{datetime.now():%Y-%m-%d_%H-%M-%S}_{name}.csv")
        self.marker_count = 0
        self.engine.send("start_log", path, bool(self.settings.get("log_raw_columns", True)), self.temp_unit)

    def _on_log(self, active, path, rows):
        if active:
            self.logging_path = active
            self.log_started = time.time()
            self.log_rows = 0
            self.log_btn.configure(text="■  Stop logging", fg_color=C["rec"], hover_color=C["rec_hover"])
            self._log_tick(reschedule=False)
        else:
            was_logging = self.logging_path
            self.logging_path = None
            self.log_btn.configure(text="Start logging", fg_color=C["accent"], hover_color=C["accent_hover"])
            if was_logging and path:
                self.log_lbl.configure(text=f"Saved {rows} rows to {path}", text_color=C["ok"])
                self._status(f"Log saved: {rows} rows.", "ok")
            else:
                self.log_lbl.configure(text=LOG_HINT, text_color=C["muted"])

    def _log_tick(self, reschedule=True):
        if self.logging_path:
            self._blink = not self._blink
            secs = int(time.time() - self.log_started)
            self.log_lbl.configure(
                text=f"{'●' if self._blink else '○'}  Recording   {secs // 60:02d}:{secs % 60:02d}   "
                     f"{self.log_rows} rows   {os.path.basename(self.logging_path)}", text_color=C["rec"])
        if reschedule:
            self.after(500, self._log_tick)

    def _add_marker(self):
        if not self.logging_path:
            self._status("Markers go into the log file, so start logging first.", "warn")
            return
        self.marker_count += 1
        label = f"M{self.marker_count}"
        self.engine.send("marker", label)
        self.chart_markers.append((time.time(), label))
        self._mark_chart()
        self._status(f"Marker {label} added to the log.", "ok")

    # ------------------------------------------------------------ live values
    def _unit_text(self, p) -> str:
        u = p.units_for(self.temp_unit)
        return "" if u == "raw" else u

    def _set_temp_unit(self, value: str):
        unit = "C" if "C" in value else "F"
        if unit == self.temp_unit:
            return
        if self.logging_path:
            self.temp_seg.set(f"\u00b0{self.temp_unit}")
            self._status("Stop logging before changing temperature units, so the log's units stay the same.", "warn")
            return
        self.temp_unit = unit
        self.settings["temp_unit"] = unit
        self._write_settings()
        for i, t in self.tiles.items():
            self._set(t, "unit", text=self._unit_text(self.pids[i]))
            self._render_tile(i)
        self._mark_chart()
        self._status(f"Temperatures now shown in \u00b0{unit}.", "ok")

    def _render_tile(self, i: int):
        t = self.tiles.get(i)
        if t is None or i not in self.last_values or i >= len(self.pids):
            return
        val, rawhex, err = self.last_values[i]
        p = self.pids[i]
        if val is None:
            self._set(t, "val", text="--", text_color=C["stale"])
            self._set(t, "sub", text=(err or "No data")[:70], text_color=C["bad"])
            return
        rng = t["range"]                     # low/high are kept in the channel's own units
        rng[0] = val if rng[0] is None else min(rng[0], val)
        rng[1] = val if rng[1] is None else max(rng[1], val)
        lo = p.gauge_min if p.gauge_min is not None else rng[0]
        hi = p.gauge_max if p.gauge_max is not None else rng[1]
        frac = min(1.0, max(0.0, (val - lo) / (hi - lo))) if hi > lo else 0.0
        if abs(t.get("frac", -1) - frac) > 0.002:
            t["frac"] = frac
            t["bar"].set(frac)
        live = self.link_state == "connected"
        u = self.temp_unit
        self._set(t, "val", text=fmt_value(p.convert(val, u)), text_color=C["ink"] if live else C["stale"])
        text = f"low {fmt_value(p.convert(rng[0], u))}    high {fmt_value(p.convert(rng[1], u))}"
        if self.settings.get("advanced", True):
            text = f"raw {rawhex}    " + text
        if err:
            text += "    last read failed"
        self._set(t, "sub", text=text, text_color=C["warn"] if err else C["muted"])

    def _on_values(self, values: dict, rate: float):
        now = time.time()
        if self.logging_path:
            self.log_rows += 1
        for i, (val, rawhex, err) in values.items():
            if i >= len(self.pids):
                continue
            p = self.pids[i]
            if rawhex:
                self.last_raw[p.pid] = bytes.fromhex(rawhex)
            if val is not None:
                self.history.setdefault(i, deque(maxlen=6000)).append((now, val))
            self.last_values[i] = (val, rawhex, err)
            self._render_tile(i)
        if rate:
            self.rate_lbl.configure(text=f"  {rate:.1f} updates/s  ", fg_color=C["chip"])
        self.chart_dirty = False          # draw now so the chart and tiles show the same reading
        try:
            self._draw_chart()
        except tk.TclError:
            pass

    def _mark_chart(self):
        self.chart_dirty = True

    def _chart_tick(self):
        if self.chart_dirty and self.current_page == "Live data":
            self.chart_dirty = False
            try:
                self._draw_chart()
            except tk.TclError:
                pass
        self.after(200, self._chart_tick)

    def _draw_chart(self):
        if self.current_page != "Live data":
            return
        c, px = self.chart, self.px
        c.delete("all")
        w, h = c.winfo_width(), c.winfo_height()
        if w < px(260) or h < px(80):
            return
        label_w, axis_h = px(190), px(24)
        x0, x1 = label_w, w - px(2)
        pw = x1 - x0
        top, bottom = px(2), h - axis_h
        span = self.chart_seconds
        now = time.time()

        for k in range(7):
            x = x0 + pw * k / 6
            c.create_line(x, top, x, bottom, fill=C["grid"])
            secs = span * (6 - k) / 6
            text = "now" if k == 6 else f"-{secs:g} s"
            anchor = "ne" if k == 6 else ("nw" if k == 0 else "n")
            c.create_text(x, bottom + px(6), text=text, anchor=anchor, fill=C["muted"], font=self.cf(9))
        c.create_line(x0, bottom, x1, bottom, fill=C["line"])

        if not self.charted:
            c.create_text(x0 + pw / 2, (top + bottom) / 2, fill=C["muted"], font=self.cf(11),
                          text="Nothing on the chart yet. Press Chart on any tile, or Chart all.")
            return

        lane = (bottom - top) / len(self.charted)
        compact = lane < px(54)
        for k, i in enumerate(self.charted):
            p = self.pids[i]
            color = self._color(i)
            ya, yb = top + k * lane, top + (k + 1) * lane
            if k:
                c.create_line(0, ya, x1, ya, fill=C["grid"])
            inset = min(px(8), lane * 0.18)
            c.create_rectangle(0, ya + inset, px(4), yb - inset, fill=color, outline="")
            pts = [(t, p.convert(v, self.temp_unit)) for t, v in self.history.get(i, ()) if now - t <= span]
            u = p.units_for(self.temp_unit)
            units = "" if u == "raw" else f" {u}"
            current = f"{fmt_value(pts[-1][1])}{units}" if pts else "--"
            mid = (ya + yb) / 2
            if compact:
                c.create_text(px(14), mid, anchor="w", text=f"{p.name}   {current}", fill=C["ink"],
                              font=self.cf(9, "bold"))
            else:
                c.create_text(px(14), mid - px(10), anchor="w", text=p.name, fill=C["muted"], font=self.cf(9))
                c.create_text(px(14), mid + px(10), anchor="w", text=current, fill=C["ink"], font=self.cf(12, "bold"))
            if len(pts) < 2:
                continue
            vals = [v for _, v in pts]
            lo, hi = min(vals), max(vals)
            ya2, yb2 = ya + inset, yb - inset
            coords = []
            for t, v in pts:
                coords.append(x0 + pw * (1 - (now - t) / span))
                coords.append(yb2 - (yb2 - ya2) * (v - lo) / (hi - lo) if hi != lo else mid)
            c.create_line(*coords, fill=color, width=max(2, px(2)), capstyle="round", joinstyle="round")
            if not compact and hi != lo:
                c.create_text(x0 + px(6), ya2, anchor="nw", text=fmt_value(hi), fill=C["muted"], font=self.cf(8))
                c.create_text(x0 + px(6), yb2, anchor="sw", text=fmt_value(lo), fill=C["muted"], font=self.cf(8))

        for t, label in self.chart_markers:
            if now - t <= span:
                x = x0 + pw * (1 - (now - t) / span)
                c.create_line(x, top, x, bottom, fill=C["warn"], dash=(4, 3))
                c.create_text(x + px(4), top + px(2), text=label, anchor="nw", fill=C["warn"],
                              font=self.cf(8, "bold"))

    # --------------------------------------------------------------- channels
    def _can_edit_channels(self) -> bool:
        if self.logging_path:
            messagebox.showinfo("Logging in progress",
                                "Stop logging before changing channels, so the log's columns "
                                "stay the same from start to finish.")
            return False
        return True

    def _selected_channel(self):
        sel = self.ch_tree.selection()
        return int(sel[0]) if sel else None

    def _channels_changed(self, select=None, save=True):
        if save:
            try:
                kl.save_pids(PIDS_PATH, self.pids, self.channels_revision)
            except OSError as e:
                messagebox.showerror("Could not save", f"pids.json could not be written:\n{e}")
        self.history.clear()
        self.last_values.clear()
        self._rebuild_tiles()
        self._save_charted()
        self._refresh_channel_list()
        if select is not None and 0 <= select < len(self.pids):
            self.ch_tree.selection_set(str(select))
            self.ch_tree.see(str(select))
        self.engine.send("set_pids", [p.copy() for p in self.pids])

    def _add_channel(self):
        if not self._can_edit_channels():
            return
        dlg = ChannelDialog(self, None, "Add channel")
        if dlg.result:
            self.pids.append(dlg.result)
            self._channels_changed(select=len(self.pids) - 1)

    def _edit_channel(self):
        i = self._selected_channel()
        if i is None:
            messagebox.showinfo("Pick a channel", "Select a channel in the list first.")
            return
        if not self._can_edit_channels():
            return
        old_name = self.pids[i].name
        dlg = ChannelDialog(self, self.pids[i], "Edit channel")
        if dlg.result:
            self.pids[i] = dlg.result
            names = self.settings.get("charted", [])
            if old_name in names:                      # keep a renamed channel on the chart
                self.settings["charted"] = [dlg.result.name if n == old_name else n for n in names]
            self._channels_changed(select=i)

    def _toggle_channel(self):
        i = self._selected_channel()
        if i is None or not self._can_edit_channels():
            return
        self.pids[i].enabled = not self.pids[i].enabled
        self._channels_changed(select=i)

    def _move_channel(self, delta: int):
        i = self._selected_channel()
        if i is None or not self._can_edit_channels():
            return
        j = i + delta
        if 0 <= j < len(self.pids):
            self.pids[i], self.pids[j] = self.pids[j], self.pids[i]
            self._channels_changed(select=j)

    def _remove_channel(self):
        i = self._selected_channel()
        if i is None or not self._can_edit_channels():
            return
        if messagebox.askyesno("Remove channel", f"Remove {self.pids[i].name}?"):
            del self.pids[i]
            self._channels_changed()

    def _reset_channels(self):
        if not self._can_edit_channels():
            return
        if not messagebox.askyesno(
                "Reset channels",
                "Replace ALL channels with the built-in defaults?\n\n"
                "This removes any channels you added and any edits or calibration you made, such as the throttle "
                "end points. Your cable, units and other settings are kept."):
            return
        self._apply_default_channels()

    def _apply_default_channels(self):
        self.pids = [p.copy() for p in kl.DEFAULT_PIDS]
        self.channels_revision = kl.DEFAULTS_REVISION
        self._channels_changed()
        self._status("Channels reset to the defaults.", "ok")

    def _check_channel_defaults(self):
        """After an update, offer the new default channels once if the saved list is older."""
        if self.channels_revision >= kl.DEFAULTS_REVISION:
            return
        yes = messagebox.askyesno(
            "New default channels",
            f"This version has updated default channels: {kl.DEFAULTS_NOTE}.\n\n"
            "Replace your channel list with the new defaults?\n\n"
            "Choose Yes unless you added channels or calibrated the throttle yourself. Choose No to keep "
            "your list; you can still reset any time from the Channels page.", parent=self)
        if yes:
            self._apply_default_channels()
        else:
            self.channels_revision = kl.DEFAULTS_REVISION          # remember the answer; ask again next time
            try:
                kl.save_pids(PIDS_PATH, self.pids, self.channels_revision)
            except OSError:
                pass

    def _reload_channels(self):
        if not self._can_edit_channels():
            return
        try:
            pids, problems = kl.load_pids(PIDS_PATH)
        except (OSError, ValueError) as e:
            messagebox.showerror("Channel file problem", f"Could not read pids.json:\n{e}")
            return
        self.pids = pids
        self.channels_revision = kl.pids_revision(PIDS_PATH)
        self._channels_changed(save=False)
        if problems:
            messagebox.showwarning("Some channels were skipped", "\n".join(problems))
        self._status("Channels reloaded from pids.json.", "ok")

    # ---------------------------------------------------------------- scanner
    def _scan(self):
        if self.link_state != "connected":
            messagebox.showinfo("Not connected", "Connect to the ECU first, then scan.")
            return
        try:
            lo, hi = kl.parse_pid(self.scan_from.get()), kl.parse_pid(self.scan_to.get())
        except ValueError:
            messagebox.showerror("Scan range", "Use hex PIDs between 0x00 and 0xFF.")
            return
        if lo > hi:
            lo, hi = hi, lo
        self.scan_tree.delete(*self.scan_tree.get_children())
        self.scan_rows = {}
        self.scan_total, self.scan_count = hi - lo + 1, 0
        self.scan_prog.set(f"Scanning 0x{lo:02X} to 0x{hi:02X}. Live data pauses until the scan ends.")
        self.engine.send("scan", lo, hi)

    def _watch(self):
        answering = [pid for pid, row in sorted(self.scan_rows.items()) if row["result"] == "answers"]
        if not answering:
            messagebox.showinfo("Nothing to watch", "Run a scan first. Watch re-reads the PIDs that answered.")
            return
        if self.link_state != "connected":
            messagebox.showinfo("Not connected", "Connect to the ECU first.")
            return
        chosen = [int(iid, 16) for iid in self.scan_tree.selection()]
        pids = sorted(p for p in chosen if p in answering) or answering
        for pid in pids:
            self.scan_rows[pid]["fresh"] = True     # Low, High and Changes restart from the next reading
        which = f"{len(pids)} selected PIDs" if len(pids) < len(answering) or chosen else f"all {len(pids)} PIDs"
        self.scan_prog.set(f"Watching {which}. Change what you want to find now, then sort by Swing. "
                           "Press Stop to go back to live data.")
        self.engine.send("watch", pids)

    def _sort_scan(self, col: str):
        tree = self.scan_tree

        def num(text):
            s = str(text).strip().rstrip("%")
            try:
                return float(int(s, 16)) if s.lower().startswith("0x") else float(s)
            except ValueError:
                return None

        keyed = [(num(tree.set(iid, col)), iid) for iid in tree.get_children("")]
        last_col, last_desc = self.scan_sort
        desc = (not last_desc) if last_col == col else col in ("bytes", "value", "low", "high", "swing", "changes")
        self.scan_sort = (col, desc)
        have = sorted([k for k in keyed if k[0] is not None], key=lambda k: k[0], reverse=desc)
        order = [iid for _, iid in have] + [iid for v, iid in keyed if v is None]
        for index, iid in enumerate(order):
            tree.move(iid, "", index)

    def _put_scan_row(self, pid: int, row: dict, tags=None):
        answered = row["result"] == "answers"
        if not answered and not self.scan_show_all.get():
            return
        iid = f"{pid:02X}"
        lo, hi = row.get("lo"), row.get("hi")
        swing = f"{(hi - lo) * 100.0 / hi:.1f}%" if hi else ""
        values = (f"0x{pid:02X}", row["result"], row["n"] or "", row["data"],
                  "" if row.get("val") is None else row["val"], "" if lo is None else lo, "" if hi is None else hi,
                  swing, row["changes"])
        if tags is None:
            tags = () if answered else ("dim",)
        if self.scan_tree.exists(iid):
            self.scan_tree.item(iid, values=values, tags=tags)
        else:
            self.scan_tree.insert("", "end", iid=iid, values=values, tags=tags)

    def _refill_scan_tree(self):
        self.scan_tree.delete(*self.scan_tree.get_children())
        for pid, row in sorted(self.scan_rows.items()):
            self._put_scan_row(pid, row)

    def _on_scan(self, pid: int, result: str, data: str, n: int):
        val = int.from_bytes(bytes.fromhex(data), "big") if data else None
        row = {"result": result, "data": data, "n": n, "changes": 0, "first": data,
               "val": val, "lo": val, "hi": val}
        self.scan_rows[pid] = row
        if data:
            self.last_raw[pid] = bytes.fromhex(data)
        self._put_scan_row(pid, row)
        self.scan_count += 1
        answered = sum(1 for r in self.scan_rows.values() if r["result"] == "answers")
        self.scan_prog.set(f"Scanned {self.scan_count} of {self.scan_total}. {answered} answered so far.")

    def _on_watch(self, pid: int, data: str):
        row = self.scan_rows.get(pid)
        if row is None:
            return
        if data:
            self.last_raw[pid] = bytes.fromhex(data)
        val = int.from_bytes(bytes.fromhex(data), "big") if data else None
        fresh = row.pop("fresh", False)
        if fresh:                                   # first reading after Watch was pressed: start measuring from here
            row["lo"] = row["hi"] = val
            row["changes"] = 0
        changed = data != row["data"] and not fresh
        if not changed and not fresh:
            return
        if changed:
            row["changes"] += 1
        row["data"], row["val"] = data, val
        if val is not None:
            row["lo"] = val if row.get("lo") is None else min(row["lo"], val)
            row["hi"] = val if row.get("hi") is None else max(row["hi"], val)
        self._put_scan_row(pid, row, tags=("changed",) if changed else None)
        if changed:
            iid = f"{pid:02X}"
            self.after(700, lambda iid=iid: self.scan_tree.exists(iid) and self.scan_tree.item(iid, tags=()))

    def _on_scan_done(self):
        if self.scan_rows:
            answered = sum(1 for r in self.scan_rows.values() if r["result"] == "answers")
            moving = sum(1 for r in self.scan_rows.values() if r["changes"])
            hidden = 0 if self.scan_show_all.get() else len(self.scan_rows) - answered
            msg = f"Done. {answered} PIDs answered"
            msg += f", {moving} changed while watching." if moving else "."
            if hidden:
                msg += f" {hidden} that didn't answer are hidden."
            self.scan_prog.set(msg + " Live data has resumed.")

    def _scan_to_channels(self):
        sel = self.scan_tree.selection()
        if not sel:
            messagebox.showinfo("Pick rows", "Select one or more answering rows first (Ctrl-click to pick several).")
            return
        if not self._can_edit_channels():
            return
        added = 0
        for iid in sel:
            pid = int(iid, 16)
            row = self.scan_rows.get(pid, {})
            if row.get("result") != "answers":
                continue
            self.pids.append(kl.PidDef(f"PID 0x{pid:02X}", pid, "raw", "raw", True, 1, False,
                                       f"Added from a PID scan. First answer: {row.get('first', '')}"))
            added += 1
        if added:
            self._channels_changed(select=len(self.pids) - 1)
            self._status(f"Added {added} channel(s). Rename and scale them in Channels.", "ok")

    def _save_scan(self):
        if not self.scan_rows:
            messagebox.showinfo("Nothing to save", "Run a scan first.")
            return
        path = filedialog.asksaveasfilename(
            defaultextension=".csv", initialdir=self._log_dir(),
            initialfile=f"pid_scan_{datetime.now():%Y-%m-%d_%H-%M-%S}.csv",
            filetypes=[("CSV", "*.csv")])
        if not path:
            return
        with open(path, "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["pid", "result", "bytes", "first_answer", "last_answer", "value", "low", "high",
                        "changes_seen"])
            for pid, r in sorted(self.scan_rows.items()):
                w.writerow([f"0x{pid:02X}", r["result"], r["n"], r["first"], r["data"],
                            "" if r.get("val") is None else r["val"], "" if r.get("lo") is None else r["lo"],
                            "" if r.get("hi") is None else r["hi"], r["changes"]])
        self._status(f"Scan saved to {path}", "ok")

    # ---------------------------------------------------------------- console
    def _console(self, text: str, tag: str = "note"):
        t = self.console_text
        t.configure(state="normal")
        t.insert("end", text + "\n", tag)
        lines = int(t.index("end-1c").split(".")[0])
        if lines > 3000:
            t.delete("1.0", f"{lines - 3000}.0")
        t.see("end")
        t.configure(state="disabled")

    def _console_clear(self):
        self.console_text.configure(state="normal")
        self.console_text.delete("1.0", "end")
        self.console_text.configure(state="disabled")

    def _console_send(self):
        text = self.console_var.get().strip()
        if not text:
            return
        try:
            payload = kl.parse_hex(text)
        except ValueError:
            self._console(f"'{text}' is not hex. Example: 21 09", "bad")
            return
        self._console(f"Request: {kl.hexs(payload)}", "note")
        self.engine.send("raw", payload)

    def _on_traffic(self, t: float, direction: str, hexstr: str):
        if self.show_traffic.get() and self.settings.get("advanced", True):
            stamp = datetime.fromtimestamp(t).strftime("%H:%M:%S.%f")[:-3]
            self._console(f"{stamp}  {direction}  {hexstr}", "tx" if direction == "TX" else "rx")

    def _toggle_traffic_file(self):
        if self.save_traffic.get():
            path = os.path.join(self._log_dir(), f"traffic_{datetime.now():%Y-%m-%d_%H-%M-%S}.txt")
            self.engine.send("traffic_file", path)
        else:
            self.engine.send("traffic_file", None)
            self._status("Stopped saving bus traffic.", "info")

    # --------------------------------------------------------------- settings
    def _write_settings(self):
        try:
            kl.save_settings(SETTINGS_PATH, self.settings)
        except OSError as e:
            self._status(f"Could not save settings: {e}", "bad")

    def _save_settings(self, quiet=False) -> bool:
        new = dict(self.settings)
        for key, var in self.set_vars.items():
            v = var.get()
            new[key] = v.strip() if isinstance(v, str) else bool(v)
        try:
            for key in ("baud", "request_gap_ms", "response_timeout_ms", "fast_baud"):
                new[key] = kl.to_int(new[key])
            addr = str(new["ecu_addr"]).strip().lower()
            new["ecu_addr"] = "auto" if addr in ("", "auto") else f"0x{kl.parse_pid(addr):02X}"
            kl.parse_pid(new["tester_addr"])
            if new["diag_session"]:
                kl.parse_pid(new["diag_session"])
            if new["fast_switch_cmd"]:
                kl.parse_hex(new["fast_switch_cmd"])
        except ValueError as e:
            messagebox.showerror("Check the settings", f"One of the values in Settings isn't valid:\n{e}")
            return False
        self.settings = new
        self.set_vars["ecu_addr"].set(new["ecu_addr"])
        self._write_settings()
        if not quiet:
            self._status("Settings saved. They take effect the next time you connect.", "ok")
        return True

    # ------------------------------------------------------------- plumbing
    def _status(self, text: str, level: str = "info"):
        color = {"info": C["muted"], "ok": C["ok"], "warn": C["warn"], "bad": C["bad"]}.get(level, C["muted"])
        self.status_var.set(text)
        self.status_lbl.configure(text_color=color)

    def _drain_events(self):
        handlers = {
            "values": self._on_values, "state": self._on_state, "status": self._status,
            "log": self._on_log, "scan": self._on_scan, "watch": self._on_watch,
            "scan_done": self._on_scan_done, "traffic": self._on_traffic,
            "console": lambda text: self._console(text, "note"),
        }
        for _ in range(500):
            try:
                ev = self.events.get_nowait()
            except queue.Empty:
                break
            try:
                handlers[ev[0]](*ev[1:])
            except Exception:
                self._console(traceback.format_exc(), "bad")
        self.after(50, self._drain_events)

    def _on_close(self):
        if self.logging_path and not messagebox.askyesno("Quit", "A log is still recording. Stop it and quit?"):
            return
        self.engine.send("quit")
        self.engine.join(timeout=3.0)
        self.destroy()


class ChannelDialog(ctk.CTkToplevel if ctk else object):
    def __init__(self, app: App, pid_def, title: str):
        super().__init__(app, fg_color=C["bg"])
        self.app = app
        self.result = None
        self.title(title)
        self.transient(app)
        self.resizable(False, False)
        if sys.platform == "win32" and os.path.exists(ICON_PATH):
            self.after(250, lambda: self.iconbitmap(ICON_PATH))   # after CustomTkinter's own icon call
        p = pid_def or kl.PidDef("New channel", 0x00, "raw", "raw")

        def opt(v):
            return "" if v is None else fmt_value(v)

        self.v_name = tk.StringVar(value=p.name)
        self.v_pid = tk.StringVar(value=f"0x{p.pid:02X}")
        self.v_formula = tk.StringVar(value=p.formula)
        self.v_units = tk.StringVar(value=p.units)
        self.v_every = tk.StringVar(value=str(p.every))
        self.v_gmin = tk.StringVar(value=opt(p.gauge_min))
        self.v_gmax = tk.StringVar(value=opt(p.gauge_max))
        self.v_enabled = tk.BooleanVar(value=p.enabled)
        self.v_verified = tk.BooleanVar(value=p.verified)

        card = app._card(self)
        card.pack(fill="both", expand=True, padx=18, pady=(18, 0))
        card.grid_columnconfigure(1, weight=1)
        fields = (("Name", self.v_name, 300), ("PID (hex)", self.v_pid, 90), ("Formula", self.v_formula, 300),
                  ("Units", self.v_units, 110), ("Read every N updates", self.v_every, 90),
                  ("Gauge low", self.v_gmin, 110), ("Gauge high", self.v_gmax, 110))
        for r, (label, var, width) in enumerate(fields):
            ctk.CTkLabel(card, text=label, font=app.f(13), text_color=C["ink"], anchor="w").grid(
                row=r, column=0, sticky="w", padx=(18, 14), pady=(14 if r == 0 else 5, 5))
            ctk.CTkEntry(card, textvariable=var, width=width, height=34, corner_radius=8, border_width=1,
                         border_color=C["line"], fg_color=C["panel"], text_color=C["ink"], font=app.f(13)).grid(
                row=r, column=1, sticky="w", padx=(0, 18), pady=(14 if r == 0 else 5, 5))
        r = len(fields)
        try:                                       # shorter notes box on small screens so the dialog still fits
            notes_h = 100 if app.winfo_screenheight() / app.s >= 900 else 64
        except (tk.TclError, TypeError, ZeroDivisionError):
            notes_h = 100
        ctk.CTkLabel(card, text="Notes", font=app.f(13), text_color=C["ink"], anchor="nw").grid(
            row=r, column=0, sticky="nw", padx=(18, 14), pady=(10, 5))
        self.notes_box = ctk.CTkTextbox(card, width=520, height=notes_h, corner_radius=8, border_width=1,
                                        border_color=C["line"], fg_color=C["panel"], text_color=C["ink"],
                                        font=app.f(13), wrap="word")
        self.notes_box.grid(row=r, column=1, sticky="w", padx=(0, 18), pady=5)
        self.notes_box.insert("1.0", p.notes)
        r += 1
        ctk.CTkLabel(card, font=app.f(12), text_color=C["muted"], justify="left", anchor="w",
                     text="Formula letters: A is the first data byte, B the second, and so on. raw is all the bytes as "
                          "one number, sraw the same but signed.\nExamples:  A*100 + B    (A - 48) / 1.6    "
                          "raw * 100 / 1024\nRead every: 1 for fast channels like RPM, 10 or more for slow ones like "
                          "temperature.\nGauge low and high set the tile's bar. Leave them blank to scale it to the "
                          "values seen.\nTemperatures: make the formula give degrees C and set Units to \u00b0C, "
                          "and the tile, chart and log can switch between \u00b0C and \u00b0F."
                     ).grid(row=r, column=0, columnspan=2, sticky="w", padx=18, pady=(8, 6))
        r += 1

        # Two-point calibration: capture the reading at 0 % and at 100 %, and the formula writes itself.
        self.cal = {"closed": None, "open": None}
        self._cal_busy = False
        self.cal_var = tk.StringVar(value="")
        cal = ctk.CTkFrame(card, fg_color="transparent")
        cal.grid(row=r, column=0, columnspan=2, sticky="w", padx=18, pady=(4, 10))
        ctk.CTkLabel(cal, text="Calibrate to 0 to 100 %", font=app.f(13, "bold"), text_color=C["ink"],
                     anchor="w").grid(row=0, column=0, columnspan=3, sticky="w")
        ctk.CTkLabel(cal, font=app.f(12), text_color=C["muted"], anchor="w", justify="left",
                     text="Connect first. Press a button, then hold the throttle in that position. "
                          "The reading is taken 3 seconds later.").grid(row=1, column=0, columnspan=3,
                                                                       sticky="w", pady=(0, 6))
        app._button(cal, "Capture 0 % (closed)", lambda: self._capture("closed"), width=180, height=32).grid(
            row=2, column=0, sticky="w")
        app._button(cal, "Capture 100 % (wide open)", lambda: self._capture("open"), width=200, height=32).grid(
            row=2, column=1, sticky="w", padx=(8, 0))
        ctk.CTkLabel(cal, textvariable=self.cal_var, font=app.f(12), text_color=C["ink"], anchor="w",
                     justify="left", wraplength=520, height=18).grid(row=3, column=0, columnspan=3, sticky="w",
                                                                    pady=(4, 0))
        r += 1

        for var, text in ((self.v_enabled, "Switched on"),
                          (self.v_verified, "Scaling checked against a known-good reading")):
            ctk.CTkSwitch(card, text=text, variable=var, onvalue=True, offvalue=False, font=app.f(13),
                          text_color=C["ink"], progress_color=C["accent"]).grid(
                row=r, column=0, columnspan=2, sticky="w", padx=18, pady=4)
            r += 1
        test = ctk.CTkFrame(card, fg_color="transparent")
        test.grid(row=r, column=0, columnspan=2, sticky="w", padx=18, pady=(10, 16))
        app._button(test, "Try formula", self._try, width=110, height=32).pack(side="left")
        self.test_var = tk.StringVar(value="")
        ctk.CTkLabel(test, textvariable=self.test_var, font=app.f(12), text_color=C["ink"]).pack(side="left", padx=12)

        btns = ctk.CTkFrame(self, fg_color="transparent")
        btns.pack(fill="x", padx=18, pady=16)
        app._button(btns, "Cancel", self.destroy, width=100).pack(side="right")
        app._button(btns, "Save channel", self._save, "primary", width=130).pack(side="right", padx=(0, 8))

        self.bind("<Escape>", lambda e: self.destroy())
        self.after(20, self._center)
        self.after(150, self._grab)
        self.wait_window(self)

    def _center(self):
        try:
            self.update_idletasks()
            x = self.app.winfo_rootx() + (self.app.winfo_width() - self.winfo_width()) // 2
            y = self.app.winfo_rooty() + (self.app.winfo_height() - self.winfo_height()) // 3
            self.geometry(f"+{max(0, x)}+{max(0, y)}")
        except (tk.TclError, TypeError):
            pass

    def _grab(self):
        try:
            self.grab_set()
            self.focus_force()
        except tk.TclError:
            pass

    def _capture(self, which: str):
        if self._cal_busy:
            return
        try:
            pid = kl.parse_pid(self.v_pid.get())
        except ValueError:
            self.cal_var.set("Enter a valid PID first.")
            return
        if self.app.link_state != "connected":
            self.cal_var.set("Connect to the ski (or Demo) first, so there are live readings to capture.")
            return
        self._cal_busy = True
        self._cal_count(which, pid, 3)

    def _cal_count(self, which: str, pid: int, n: int):
        if not self.winfo_exists():
            return
        if n > 0:
            where = "closed" if which == "closed" else "wide open"
            self.cal_var.set(f"Hold it {where}... reading in {n}")
            self.after(1000, lambda: self._cal_count(which, pid, n - 1))
        else:
            self.cal_var.set("Reading...")
            self._cal_sample(which, pid, [], 0)

    def _cal_sample(self, which: str, pid: int, samples: list, k: int):
        if not self.winfo_exists():
            return
        data = self.app.last_raw.get(pid)
        if data:
            samples.append(int.from_bytes(data, "big"))
        if k < 10:                                   # about 1 second of readings, then take the middle one
            self.after(100, lambda: self._cal_sample(which, pid, samples, k + 1))
            return
        self._cal_busy = False
        if not samples:
            self.cal_var.set(f"No readings for PID 0x{pid:02X}. Check the channel is switched on and connected.")
            return
        self.cal[which] = sorted(samples)[len(samples) // 2]
        c, o = self.cal["closed"], self.cal["open"]
        if c is None or o is None:
            have = "0 %" if o is None else "100 %"
            need = "100 % (wide open)" if o is None else "0 % (closed)"
            self.cal_var.set(f"Captured {have} = {self.cal[which]}. Now capture {need}.")
        elif c == o:
            self.cal_var.set(f"Both readings are {c}, so there's nothing to scale. Move the throttle between "
                             "the two captures.")
        else:
            self.v_formula.set(f"max(0, min(100, (raw - {c}) * 100 / ({o} - {c})))")
            self.v_units.set("%")
            self.v_gmin.set("0")
            self.v_gmax.set("100")
            self.cal_var.set(f"0 % = {c}, 100 % = {o}. Formula, units and gauge are filled in. "
                             "Press Save channel to keep them.")

    def _build(self) -> kl.PidDef:
        return kl.PidDef(self.v_name.get(), self.v_pid.get(), self.v_formula.get(), self.v_units.get(),
                         self.v_enabled.get(), int(self.v_every.get() or 1), self.v_verified.get(),
                         self.notes_box.get("1.0", "end-1c").strip(), self.v_gmin.get(), self.v_gmax.get())

    def _try(self):
        try:
            d = self._build()
            data = self.app.last_raw.get(d.pid)
            if data is None:
                zero = d.decode(bytes(2))
                self.test_var.set(f"No bytes seen for PID 0x{d.pid:02X} yet. With 00 00 the formula gives "
                                  f"{fmt_value(zero)} {d.units}.")
            else:
                self.test_var.set(f"Latest bytes {kl.hexs(data)} give {fmt_value(d.decode(data))} {d.units}.")
        except Exception as e:
            self.test_var.set(f"Problem: {e}")

    def _save(self):
        try:
            self.result = self._build()
        except Exception as e:
            messagebox.showerror("Can't save this channel", str(e), parent=self)
            return
        self.destroy()


def main():
    if ctk is None:
        msg = ("This version of the logger needs the customtkinter package.\n\n"
               "Open a terminal in the app folder and run:\n    pip install customtkinter")
        print(msg)
        try:
            root = tk.Tk()
            root.withdraw()
            messagebox.showerror("Missing package", msg)
            root.destroy()
        except tk.TclError:
            pass
        sys.exit(1)
    App().mainloop()


if __name__ == "__main__":
    main()
