#!/usr/bin/env python3
"""
Door Sentinel — Desktop Monitoring GUI
=======================================
A modern Linux desktop app for real-time security monitoring,
remote camera control, and ML analysis.

Usage:
    python sentinel_gui.py
"""

import os
import sys
import json
import time
import threading
import tempfile
from pathlib import Path
from datetime import datetime

import json

import customtkinter as ctk
from PIL import Image, ImageTk
import requests

import firebase_admin
from firebase_admin import credentials, db

# ── Configuration ──────────────────────────────────────────────────────────────

BASE_DIR = Path(__file__).parent
SERVICE_ACCOUNT_KEY = BASE_DIR / "serviceAccountKey.json"

# Load from sentinel_config.json (gitignored)
_config_file = BASE_DIR / "sentinel_config.json"
if _config_file.exists():
    with open(_config_file) as _f:
        _cfg = json.load(_f)
    FIREBASE_DB_URL = _cfg.get("databaseURL", "")
    FIREBASE_STORAGE_BUCKET = _cfg.get("storageBucket", "")
else:
    FIREBASE_DB_URL = os.environ.get("FIREBASE_DB_URL", "")
    FIREBASE_STORAGE_BUCKET = os.environ.get("FIREBASE_STORAGE_BUCKET", "")

# ── Theme ──────────────────────────────────────────────────────────────────────

ctk.set_appearance_mode("dark")
ctk.set_default_color_theme("blue")

# Custom colors matching the web dashboard
BG_PRIMARY = "#0D0F14"
BG_CARD = "#171B22"
BG_ELEVATED = "#1E2330"
ACCENT_BLUE = "#4A9EFF"
ACCENT_GREEN = "#34D399"
ACCENT_RED = "#FF5C5C"
ACCENT_AMBER = "#FBBF24"
TEXT_PRIMARY = "#E8EAF0"
TEXT_SECONDARY = "#8B95A8"
TEXT_TERTIARY = "#5A6478"
BORDER = "#252B38"

# ── Firebase Init ──────────────────────────────────────────────────────────────

def init_firebase():
    if firebase_admin._apps:
        return True
    if not SERVICE_ACCOUNT_KEY.exists():
        return False
    try:
        cred = credentials.Certificate(str(SERVICE_ACCOUNT_KEY))
        firebase_admin.initialize_app(cred, {
            "databaseURL": FIREBASE_DB_URL,
            "storageBucket": FIREBASE_STORAGE_BUCKET,
        })
        return True
    except Exception as e:
        print(f"Firebase init error: {e}")
        return False


# ══════════════════════════════════════════════════════════════════════════════
#  MAIN APPLICATION
# ══════════════════════════════════════════════════════════════════════════════

class DoorSentinelApp(ctk.CTk):
    def __init__(self):
        super().__init__()

        self.title("Door Sentinel")
        self.geometry("1200x800")
        self.minsize(900, 600)
        self.configure(fg_color=BG_PRIMARY)

        # State
        self.firebase_connected = False
        self.phone_online = False
        self.telemetry = {"temp": "--", "humidity": "--", "light": "--"}
        self.events = []
        self.ml_running = False
        self.detector = None
        self.recognizer = None
        self._poll_threads = []
        self._stop_event = threading.Event()

        # Build UI
        self._build_header()
        self._build_main()
        self._build_status_bar()

        # Try Firebase
        self.after(500, self._connect_firebase)

        # Handle close
        self.protocol("WM_DELETE_WINDOW", self._on_close)

    # ── Header ─────────────────────────────────────────────────────────────

    def _build_header(self):
        header = ctk.CTkFrame(self, fg_color=BG_CARD, corner_radius=0, height=60)
        header.pack(fill="x", padx=0, pady=0)
        header.pack_propagate(False)

        # Logo
        logo_frame = ctk.CTkFrame(header, fg_color="transparent")
        logo_frame.pack(side="left", padx=20)

        title_frame = ctk.CTkFrame(logo_frame, fg_color="transparent")
        title_frame.pack(side="left")
        ctk.CTkLabel(title_frame, text="DOOR SENTINEL", font=("Inter", 9, "bold"),
                     text_color=ACCENT_BLUE).pack(anchor="w")
        ctk.CTkLabel(title_frame, text="Security Monitor", font=("Inter", 16, "bold"),
                     text_color=TEXT_PRIMARY).pack(anchor="w")

        # Right side - status
        right = ctk.CTkFrame(header, fg_color="transparent")
        right.pack(side="right", padx=20)

        self.phone_status_label = ctk.CTkLabel(
            right, text="● Phone Offline", font=("Inter", 12, "bold"),
            text_color=ACCENT_RED
        )
        self.phone_status_label.pack(side="right", padx=10)

        self.firebase_status = ctk.CTkLabel(
            right, text="● Firebase...", font=("Inter", 12),
            text_color=TEXT_TERTIARY
        )
        self.firebase_status.pack(side="right", padx=10)

    # ── Main Layout ────────────────────────────────────────────────────────

    def _build_main(self):
        main = ctk.CTkFrame(self, fg_color="transparent")
        main.pack(fill="both", expand=True, padx=16, pady=(12, 0))

        # Telemetry row
        self._build_telemetry(main)

        # Content columns
        content = ctk.CTkFrame(main, fg_color="transparent")
        content.pack(fill="both", expand=True, pady=(12, 0))
        content.columnconfigure(0, weight=1)
        content.columnconfigure(1, weight=1)
        content.rowconfigure(0, weight=1)

        # Left column: Controls + ML
        left = ctk.CTkFrame(content, fg_color="transparent")
        left.grid(row=0, column=0, sticky="nsew", padx=(0, 6))
        self._build_controls(left)
        self._build_ml_panel(left)

        # Right column: Event Timeline
        right = ctk.CTkFrame(content, fg_color="transparent")
        right.grid(row=0, column=1, sticky="nsew", padx=(6, 0))
        self._build_timeline(right)

    # ── Telemetry ──────────────────────────────────────────────────────────

    def _build_telemetry(self, parent):
        row = ctk.CTkFrame(parent, fg_color="transparent")
        row.pack(fill="x")
        row.columnconfigure((0, 1, 2), weight=1)

        self.tel_cards = {}
        configs = [
            ("temp", "T", "Temperature", "--"),
            ("humidity", "H", "Humidity", "--"),
            ("light", "L", "Light Level", "--"),
        ]

        for i, (key, icon, label, default) in enumerate(configs):
            card = ctk.CTkFrame(row, fg_color=BG_CARD, corner_radius=14, border_width=1,
                               border_color=BORDER)
            card.grid(row=0, column=i, sticky="nsew", padx=6, pady=2)
            card.configure(height=100)
            card.pack_propagate(False)

            inner = ctk.CTkFrame(card, fg_color="transparent")
            inner.pack(expand=True)

            ctk.CTkLabel(inner, text=icon, font=("Inter", 18, "bold"),
                        text_color=ACCENT_BLUE).pack()
            val_label = ctk.CTkLabel(inner, text=default, font=("Inter", 28, "bold"),
                                     text_color=TEXT_PRIMARY)
            val_label.pack()
            ctk.CTkLabel(inner, text=label.upper(), font=("Inter", 8, "bold"),
                        text_color=TEXT_SECONDARY).pack()

            self.tel_cards[key] = val_label

    # ── Remote Controls ────────────────────────────────────────────────────

    def _build_controls(self, parent):
        card = ctk.CTkFrame(parent, fg_color=BG_CARD, corner_radius=14,
                           border_width=1, border_color=BORDER)
        card.pack(fill="x", pady=(0, 8))

        ctk.CTkLabel(card, text="REMOTE CONTROLS", font=("Inter", 9, "bold"),
                    text_color=TEXT_SECONDARY).pack(anchor="w", padx=16, pady=(14, 8))

        # Capture button (full width, prominent)
        self.capture_btn = ctk.CTkButton(
            card, text="Capture Photo", font=("Inter", 15, "bold"),
            height=48, corner_radius=12, fg_color=ACCENT_BLUE,
            hover_color="#3D8AE5", command=self._cmd_capture
        )
        self.capture_btn.pack(fill="x", padx=16, pady=(0, 8))

        # Button grid
        btn_grid = ctk.CTkFrame(card, fg_color="transparent")
        btn_grid.pack(fill="x", padx=16, pady=(0, 8))
        btn_grid.columnconfigure((0, 1), weight=1)

        self.torch_btn = ctk.CTkButton(
            btn_grid, text="Torch", font=("Inter", 12, "bold"),
            height=40, corner_radius=10,
            fg_color="#2D2200", hover_color="#3D3000",
            text_color=ACCENT_AMBER, command=self._cmd_torch
        )
        self.torch_btn.grid(row=0, column=0, sticky="ew", padx=(0, 4))
        self._torch_on = False

        self.stream_btn = ctk.CTkButton(
            btn_grid, text="Livestream", font=("Inter", 12, "bold"),
            height=40, corner_radius=10,
            fg_color="#2D0000", hover_color="#3D0000",
            text_color=ACCENT_RED, command=self._cmd_livestream
        )
        self.stream_btn.grid(row=0, column=1, sticky="ew", padx=(4, 0))
        self._stream_on = False

        self.stop_btn = ctk.CTkButton(
            card, text="Stop All", font=("Inter", 12, "bold"),
            height=36, corner_radius=10,
            fg_color=BG_ELEVATED, hover_color=BORDER,
            text_color=TEXT_SECONDARY, command=self._cmd_stop
        )
        self.stop_btn.pack(fill="x", padx=16, pady=(0, 14))

    # ── ML Panel ───────────────────────────────────────────────────────────

    def _build_ml_panel(self, parent):
        card = ctk.CTkFrame(parent, fg_color=BG_CARD, corner_radius=14,
                           border_width=1, border_color=BORDER)
        card.pack(fill="both", expand=True, pady=(0, 8))

        ctk.CTkLabel(card, text="ML ANALYSIS", font=("Inter", 9, "bold"),
                    text_color=TEXT_SECONDARY).pack(anchor="w", padx=16, pady=(14, 4))

        # ML Status
        self.ml_status = ctk.CTkLabel(card, text="Models not loaded",
                                       font=("Inter", 12), text_color=TEXT_TERTIARY)
        self.ml_status.pack(anchor="w", padx=16, pady=(0, 8))

        # Buttons
        self.ml_load_btn = ctk.CTkButton(
            card, text="Load Models", font=("Inter", 12, "bold"),
            height=40, corner_radius=10,
            fg_color=BG_ELEVATED, hover_color=BORDER,
            text_color=ACCENT_BLUE, command=self._load_ml_models
        )
        self.ml_load_btn.pack(fill="x", padx=16, pady=(0, 4))

        self.ml_run_btn = ctk.CTkButton(
            card, text="Process All Events", font=("Inter", 12, "bold"),
            height=40, corner_radius=10,
            fg_color=ACCENT_GREEN, hover_color="#2CC88C",
            text_color="#0A1A0A", command=self._run_ml,
            state="disabled"
        )
        self.ml_run_btn.pack(fill="x", padx=16, pady=(0, 4))

        self.ml_watch_btn = ctk.CTkButton(
            card, text="Watch Mode", font=("Inter", 12, "bold"),
            height=40, corner_radius=10,
            fg_color=BG_ELEVATED, hover_color=BORDER,
            text_color=ACCENT_AMBER, command=self._toggle_watch,
            state="disabled"
        )
        self.ml_watch_btn.pack(fill="x", padx=16, pady=(0, 6))

        self.ml_offline_btn = ctk.CTkButton(
            card, text="Offline Folder Analysis", font=("Inter", 12, "bold"),
            height=40, corner_radius=10,
            fg_color=BG_ELEVATED, hover_color=BORDER,
            text_color=ACCENT_BLUE, command=self._run_offline_folder,
            state="disabled"
        )
        self.ml_offline_btn.pack(fill="x", padx=16, pady=(0, 6))

        # Progress
        self.ml_progress = ctk.CTkProgressBar(card, fg_color=BG_ELEVATED,
                                               progress_color=ACCENT_BLUE, height=4)
        self.ml_progress.pack(fill="x", padx=16, pady=(0, 4))
        self.ml_progress.set(0)

        # Log
        self.ml_log = ctk.CTkTextbox(card, fg_color=BG_ELEVATED, corner_radius=8,
                                      font=("JetBrains Mono", 10), text_color=TEXT_SECONDARY,
                                      height=120, border_width=1, border_color=BORDER)
        self.ml_log.pack(fill="both", expand=True, padx=16, pady=(0, 14))

    # ── Event Timeline ─────────────────────────────────────────────────────

    def _build_timeline(self, parent):
        card = ctk.CTkFrame(parent, fg_color=BG_CARD, corner_radius=14,
                           border_width=1, border_color=BORDER)
        card.pack(fill="both", expand=True)

        header = ctk.CTkFrame(card, fg_color="transparent")
        header.pack(fill="x", padx=16, pady=(14, 8))

        ctk.CTkLabel(header, text="EVENT TIMELINE", font=("Inter", 9, "bold"),
                    text_color=TEXT_SECONDARY).pack(side="left")

        self.event_count_label = ctk.CTkLabel(header, text="0 events",
                                               font=("Inter", 10), text_color=TEXT_TERTIARY)
        self.event_count_label.pack(side="right")

        # Refresh button
        ctk.CTkButton(header, text="↻", width=28, height=28, corner_radius=8,
                      fg_color=BG_ELEVATED, hover_color=BORDER,
                      text_color=TEXT_SECONDARY, font=("", 14),
                      command=self._refresh_events).pack(side="right", padx=(0, 6))

        # Scrollable event list
        self.timeline_scroll = ctk.CTkScrollableFrame(
            card, fg_color="transparent", corner_radius=0,
            scrollbar_button_color=BORDER, scrollbar_button_hover_color=TEXT_TERTIARY
        )
        self.timeline_scroll.pack(fill="both", expand=True, padx=10, pady=(0, 10))

        # Placeholder
        self.timeline_empty = ctk.CTkLabel(
            self.timeline_scroll, text="No events yet",
            font=("Inter", 14), text_color=TEXT_TERTIARY, justify="center"
        )
        self.timeline_empty.pack(expand=True, pady=60)

    # ── Status Bar ─────────────────────────────────────────────────────────

    def _build_status_bar(self):
        bar = ctk.CTkFrame(self, fg_color=BG_CARD, corner_radius=0, height=28)
        bar.pack(fill="x", side="bottom")
        bar.pack_propagate(False)

        self.status_label = ctk.CTkLabel(bar, text="Initializing...",
                                         font=("Inter", 10), text_color=TEXT_TERTIARY)
        self.status_label.pack(side="left", padx=16)

        self.time_label = ctk.CTkLabel(bar, text="", font=("Inter", 10),
                                        text_color=TEXT_TERTIARY)
        self.time_label.pack(side="right", padx=16)
        self._update_clock()

    def _update_clock(self):
        self.time_label.configure(text=datetime.now().strftime("%H:%M:%S"))
        self.after(1000, self._update_clock)

    # ══════════════════════════════════════════════════════════════════════
    #  FIREBASE
    # ══════════════════════════════════════════════════════════════════════

    def _connect_firebase(self):
        def _do():
            ok = init_firebase()
            self.after(0, lambda: self._on_firebase_connected(ok))
        threading.Thread(target=_do, daemon=True).start()

    def _on_firebase_connected(self, ok):
        self.firebase_connected = ok
        if ok:
            self.firebase_status.configure(text="● Connected", text_color=ACCENT_GREEN)
            self.status_label.configure(text="Connected. Polling for data...")
            self._start_polling()
        else:
            self.firebase_status.configure(text="● Error", text_color=ACCENT_RED)
            self.status_label.configure(text="Missing serviceAccountKey.json — run setup.py")

    def _start_polling(self):
        """Start background threads to poll Firebase data."""
        self._stop_event.clear()

        def poll_telemetry():
            while not self._stop_event.is_set():
                try:
                    data = db.reference("telemetry/latest").get()
                    if data:
                        self.after(0, lambda d=data: self._update_telemetry(d))
                except Exception:
                    pass
                time.sleep(3)

        def poll_status():
            while not self._stop_event.is_set():
                try:
                    data = db.reference("status/phone").get()
                    online = data.get("online", False) if data else False
                    self.after(0, lambda o=online: self._update_phone_status(o))
                except Exception:
                    pass
                time.sleep(5)

        def poll_events():
            while not self._stop_event.is_set():
                try:
                    data = db.reference("logs").order_by_child("timestamp").limit_to_last(30).get()
                    if data:
                        self.after(0, lambda d=data: self._update_events(d))
                except Exception:
                    pass
                time.sleep(8)

        for fn in [poll_telemetry, poll_status, poll_events]:
            t = threading.Thread(target=fn, daemon=True)
            t.start()
            self._poll_threads.append(t)

    def _update_telemetry(self, data):
        temp = data.get("temp", "--")
        humidity = data.get("humidity", "--")
        light = data.get("light", "--")

        self.tel_cards["temp"].configure(text=f"{temp}°C" if temp != "--" else "--")
        self.tel_cards["humidity"].configure(text=f"{humidity}%" if humidity != "--" else "--")
        self.tel_cards["light"].configure(text=str(light))

    def _update_phone_status(self, online):
        self.phone_online = online
        if online:
            self.phone_status_label.configure(text="● Phone Online", text_color=ACCENT_GREEN)
        else:
            self.phone_status_label.configure(text="● Phone Offline", text_color=ACCENT_RED)

    def _update_events(self, data):
        events = []
        for key, log in sorted(data.items(), key=lambda x: x[1].get("timestamp", 0), reverse=True):
            events.append({"key": key, **log})

        self.events = events
        self.event_count_label.configure(text=f"{len(events)} events")
        self._render_events()

    def _render_events(self):
        # Clear existing
        for w in self.timeline_scroll.winfo_children():
            w.destroy()

        if not self.events:
            self.timeline_empty = ctk.CTkLabel(
                self.timeline_scroll, text="No events yet",
                font=("Inter", 14), text_color=TEXT_TERTIARY, justify="center"
            )
            self.timeline_empty.pack(expand=True, pady=60)
            return

        for event in self.events[:30]:
            self._render_event_card(event)

    def _render_event_card(self, event):
        card = ctk.CTkFrame(self.timeline_scroll, fg_color=BG_ELEVATED,
                           corner_radius=10, border_width=1, border_color=BORDER)
        card.pack(fill="x", pady=3)

        # Header row
        header = ctk.CTkFrame(card, fg_color="transparent")
        header.pack(fill="x", padx=12, pady=(10, 4))

        # Time
        ts = event.get("timestamp", 0)
        time_str = datetime.fromtimestamp(ts / 1000).strftime("%d %b %H:%M:%S") if ts else "--"
        ctk.CTkLabel(header, text=time_str, font=("Inter", 11, "bold"),
                    text_color=TEXT_SECONDARY).pack(side="left")

        # Face badge
        face_detected = event.get("faceDetected", False)
        face_count = event.get("faceCount", 0)
        if face_detected:
            badge_text = f"{face_count} face{'s' if face_count > 1 else ''}"
            badge_color = ACCENT_GREEN
        else:
            badge_text = "No faces"
            badge_color = TEXT_TERTIARY

        ctk.CTkLabel(header, text=badge_text, font=("Inter", 10, "bold"),
                    text_color=badge_color).pack(side="right")

        # File name
        fname = event.get("fileName", "")
        if fname:
            ctk.CTkLabel(card, text=fname, font=("Inter", 10),
                        text_color=TEXT_TERTIARY).pack(anchor="w", padx=12, pady=(0, 2))

        # Telemetry
        tel = event.get("telemetry")
        if tel:
            tel_text = f"{tel.get('temp', '?')}°C  /  {tel.get('humidity', '?')}%  /  {tel.get('light', '?')} lux"
            ctk.CTkLabel(card, text=tel_text, font=("Inter", 10),
                        text_color=TEXT_TERTIARY).pack(anchor="w", padx=12, pady=(0, 2))

        # ML Analysis
        ml = event.get("ml_analysis")
        if ml:
            ml_frame = ctk.CTkFrame(card, fg_color="#0D1420", corner_radius=8,
                                     border_width=1, border_color="#1A2540")
            ml_frame.pack(fill="x", padx=12, pady=(4, 2))

            ml_header = ctk.CTkFrame(ml_frame, fg_color="transparent")
            ml_header.pack(fill="x", padx=8, pady=(6, 2))

            ctk.CTkLabel(ml_header, text="ML", font=("Inter", 8, "bold"),
                        text_color=ACCENT_BLUE).pack(side="left")

            threat = ml.get("threat", {})
            level = threat.get("level", "none")
            score = threat.get("score", 0)
            level_colors = {"high": ACCENT_RED, "medium": ACCENT_AMBER,
                          "low": ACCENT_GREEN, "none": TEXT_TERTIARY}

            ctk.CTkLabel(ml_header, text=f"{level.upper()} ({score})",
                        font=("Inter", 10, "bold"),
                        text_color=level_colors.get(level, TEXT_TERTIARY)).pack(side="right")

            # Detection details
            yolo = ml.get("yolo", {})
            faces = ml.get("faces", {})

            details = []
            if yolo.get("person_count", 0) > 0:
                details.append(f"{yolo['person_count']} person(s)")
            details.append(f"{yolo.get('total_objects', 0)} objects")
            if faces.get("count", 0) > 0:
                face_text = f"{faces['count']} face(s)"
                if faces.get("identified"):
                    face_text += f" - {', '.join(faces['identified'])}"
                details.append(face_text)

            if details:
                ctk.CTkLabel(ml_frame, text="  ".join(details), font=("Inter", 10),
                            text_color=TEXT_SECONDARY, wraplength=350,
                            justify="left").pack(anchor="w", padx=8, pady=(0, 6))

        # Bottom padding
        ctk.CTkFrame(card, fg_color="transparent", height=4).pack()

    # ══════════════════════════════════════════════════════════════════════
    #  COMMANDS
    # ══════════════════════════════════════════════════════════════════════

    def _send_command(self, cmd_type, extra=None):
        if not self.firebase_connected:
            self._log_ml("error: not connected to firebase")
            return

        def _do():
            try:
                ref = db.reference("commands")
                payload = {
                    "type": cmd_type,
                    "by": "desktop_app",
                    "timestamp": {".sv": "timestamp"},
                    "status": "pending",
                }
                if extra:
                    payload.update(extra)
                ref.push(payload)
                self.after(0, lambda: self.status_label.configure(
                    text=f"Sent: {cmd_type}"
                ))
            except Exception as e:
                err_msg = str(e)
                self.after(0, lambda: self.status_label.configure(
                    text=f"Command failed: {err_msg}"
                ))

        threading.Thread(target=_do, daemon=True).start()

    def _cmd_capture(self):
        self._send_command("capture")
        self.status_label.configure(text="Sending capture...")

    def _cmd_torch(self):
        self._torch_on = not self._torch_on
        cmd = "torch_on" if self._torch_on else "torch_off"
        self._send_command(cmd)
        if self._torch_on:
            self.torch_btn.configure(text="Torch ON", fg_color=ACCENT_AMBER,
                                      text_color="#1A1A00")
        else:
            self.torch_btn.configure(text="Torch", fg_color="#2D2200",
                                      text_color=ACCENT_AMBER)

    def _cmd_livestream(self):
        self._stream_on = not self._stream_on
        if self._stream_on:
            self._send_command("livestream_start", {"fps": 3})
            self.stream_btn.configure(text="Stop Stream", fg_color=ACCENT_RED,
                                       text_color="white")
        else:
            self._send_command("livestream_stop")
            self.stream_btn.configure(text="Livestream", fg_color="#2D0000",
                                       text_color=ACCENT_RED)

    def _cmd_stop(self):
        self._send_command("stop")
        self._torch_on = False
        self._stream_on = False
        self.torch_btn.configure(text="Torch", fg_color="#2D2200",
                                  text_color=ACCENT_AMBER)
        self.stream_btn.configure(text="Livestream", fg_color="#2D0000",
                                   text_color=ACCENT_RED)

    def _refresh_events(self):
        self.status_label.configure(text="Refreshing...")
        def _do():
            try:
                data = db.reference("logs").order_by_child("timestamp").limit_to_last(30).get()
                if data:
                    self.after(0, lambda d=data: self._update_events(d))
                self.after(0, lambda: self.status_label.configure(text="Events refreshed"))
            except Exception as e:
                err_msg = str(e)
                self.after(0, lambda: self.status_label.configure(text=f"Refresh failed: {err_msg}"))
        threading.Thread(target=_do, daemon=True).start()

    # ══════════════════════════════════════════════════════════════════════
    #  ML PIPELINE
    # ══════════════════════════════════════════════════════════════════════

    def _log_ml(self, text):
        """Append text to ML log (thread-safe)."""
        def _do():
            self.ml_log.insert("end", text + "\n")
            self.ml_log.see("end")
        if threading.current_thread() is threading.main_thread():
            _do()
        else:
            self.after(0, _do)

    def _load_ml_models(self):
        self.ml_load_btn.configure(state="disabled", text="Loading...")
        self.ml_status.configure(text="Loading YOLOv8 + Face Recognition...")
        self._log_ml("loading ml models...")

        def _do():
            try:
                # Import here to avoid startup delay
                from sentinel_ml import PersonDetector, FaceRecognizer
                self.detector = PersonDetector()
                self._log_ml("yolov8 ready")
                self.recognizer = FaceRecognizer()
                self._log_ml("face recognizer ready")

                self.after(0, lambda: self._on_ml_loaded(True))
            except Exception as e:
                self._log_ml(f"error: {e}")
                self.after(0, lambda: self._on_ml_loaded(False))

        threading.Thread(target=_do, daemon=True).start()

    def _on_ml_loaded(self, ok):
        if ok:
            self.ml_status.configure(text="Models loaded. Ready.",
                                      text_color=ACCENT_GREEN)
            self.ml_load_btn.configure(text="Models Loaded", state="disabled")
            self.ml_run_btn.configure(state="normal")
            self.ml_watch_btn.configure(state="normal")
            self.ml_offline_btn.configure(state="normal")
        else:
            self.ml_status.configure(text="Failed to load models", text_color=ACCENT_RED)
            self.ml_load_btn.configure(text="Retry Load", state="normal")

    def _run_ml(self):
        if self.ml_running:
            return
        self.ml_running = True
        self.ml_run_btn.configure(state="disabled", text="Processing...")
        self._log_ml("\nprocessing all events...")

        def _do():
            try:
                from sentinel_ml import process_event, SceneAnalyzer, assess_threat
                logs = db.reference("logs").get()
                if not logs:
                    self._log_ml("no events found.")
                    return

                unprocessed = {k: v for k, v in logs.items()
                              if not v.get("ml_analysis") and v.get("imageUrl")}

                total = len(unprocessed)
                self._log_ml(f"found {total} unprocessed event(s)")

                for i, (key, data) in enumerate(unprocessed.items()):
                    self._log_ml(f"\n[{i+1}/{total}] {data.get('fileName', key)}")
                    progress = (i + 1) / max(total, 1)
                    self.after(0, lambda p=progress: self.ml_progress.set(p))

                    process_event(key, data, self.detector, self.recognizer)
                    self._log_ml("  done")

                self._log_ml(f"\nprocessed {total} event(s)")
                self.after(0, self._refresh_events)

            except Exception as e:
                self._log_ml(f"error: {e}")
            finally:
                self.ml_running = False
                self.after(0, lambda: self.ml_run_btn.configure(
                    state="normal", text="Process All Events"))
                self.after(0, lambda: self.ml_progress.set(0))

        threading.Thread(target=_do, daemon=True).start()

    def _toggle_watch(self):
        if self._stop_event.is_set() or not hasattr(self, '_watching') or not self._watching:
            self._watching = True
            self.ml_watch_btn.configure(text="Stop Watching",
                                         fg_color=ACCENT_RED, text_color="white")
            self._log_ml("\nwatch mode started")
            self._watch_thread_stop = threading.Event()

            def _watch():
                processed = set()
                while not self._watch_thread_stop.is_set():
                    try:
                        logs = db.reference("logs").get()
                        if logs:
                            for key, data in logs.items():
                                if key in processed:
                                    continue
                                if data.get("ml_analysis") or not data.get("imageUrl"):
                                    processed.add(key)
                                    continue

                                from sentinel_ml import process_event
                                self._log_ml(f"\nnew: {data.get('fileName', key)}")
                                process_event(key, data, self.detector, self.recognizer)
                                self._log_ml("  done")
                                processed.add(key)
                                self.after(0, self._refresh_events)
                    except Exception as e:
                        self._log_ml(f"error: {e}")

                    self._watch_thread_stop.wait(5)

            threading.Thread(target=_watch, daemon=True).start()
        else:
            self._watching = False
            self._watch_thread_stop.set()
            self.ml_watch_btn.configure(text="Watch Mode",
                                         fg_color=BG_ELEVATED, text_color=ACCENT_AMBER)
            self._log_ml("watch mode stopped")

    def _run_offline_folder(self):
        if self.ml_running:
            return
        
        from customtkinter import filedialog
        from pathlib import Path
        import time
        folder_path = filedialog.askdirectory(title="Select folder with images")
        if not folder_path:
            return
            
        folder = Path(folder_path)
        images = list(folder.glob("*.jpg")) + list(folder.glob("*.png")) + list(folder.glob("*.jpeg"))
        if not images:
            self._log_ml(f"\nerror: no images found in {folder_path}")
            return
            
        self.ml_running = True
        self.ml_offline_btn.configure(state="disabled", text="Processing...")
        self._log_ml(f"\n[Offline Analysis] Processing {len(images)} image(s) in {folder.name}...")

        def _do():
            try:
                from sentinel_ml import SceneAnalyzer, assess_threat
                total = len(images)
                for i, file in enumerate(images):
                    if self._stop_event.is_set():
                        break
                    self._log_ml(f"\n[{i+1}/{total}] {file.name}")
                    
                    progress = (i + 1) / max(total, 1)
                    self.after(0, lambda p=progress: self.ml_progress.set(p))
                    
                    t0 = time.time()
                    yolo_res = self.detector.detect(str(file))
                    yolo_time = time.time() - t0
                    self._log_ml(f"  yolo     {yolo_res['person_count']} person(s), {yolo_res['total_objects']} objects ({yolo_time:.2f}s)")
                    
                    t0 = time.time()
                    face_res = self.recognizer.recognize(str(file))
                    face_time = time.time() - t0
                    self._log_ml(f"  faces    {face_res['face_count']} detected ({face_time:.2f}s)")
                    if face_res["identified"]:
                        self._log_ml(f"  ident    {', '.join(face_res['identified'])}")
                        
                    scene = SceneAnalyzer.analyze(str(file))
                    self._log_ml(f"  scene    {scene.get('lighting', '?')}, brightness {scene.get('brightness', 0)}")
                    
                    threat = assess_threat(yolo_res, face_res, scene)
                    self._log_ml(f"  threat   {threat['level']} ({threat['score']})")

                self._log_ml(f"\noffline analysis complete.")

            except Exception as e:
                self._log_ml(f"error: {e}")
            finally:
                self.ml_running = False
                self.after(0, lambda: self.ml_offline_btn.configure(
                    state="normal", text="Offline Folder Analysis"))
                self.after(0, lambda: self.ml_progress.set(0))

        threading.Thread(target=_do, daemon=True).start()

    # ══════════════════════════════════════════════════════════════════════
    #  CLEANUP
    # ══════════════════════════════════════════════════════════════════════

    def _on_close(self):
        self._stop_event.set()
        if hasattr(self, '_watch_thread_stop'):
            self._watch_thread_stop.set()
        self.destroy()


# ── Entry Point ────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    app = DoorSentinelApp()
    app.mainloop()
