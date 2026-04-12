#!/usr/bin/env python3
"""
Door Sentinel - Unified Launcher
Run everything from one command.
"""

import json
import os
import sys
import subprocess
import shutil
import argparse
import time
from pathlib import Path

ROOT = Path(__file__).parent
GOOGLE_SERVICES = ROOT / "app" / "google-services.json"
ML_DIR = ROOT / "ml"
WEB_DIR = ROOT / "web"
ML_CONFIG = ML_DIR / "sentinel_config.json"
WEB_ENV = WEB_DIR / ".env.local"
SA_KEY = ML_DIR / "serviceAccountKey.json"


def banner():
    print()
    print("  Door Sentinel")
    print("  Unified Launcher")
    print("  " + "-" * 40)
    print()


def check_prereqs():
    """Check system prerequisites."""
    issues = []

    if not GOOGLE_SERVICES.exists():
        issues.append("app/google-services.json missing")

    if not shutil.which("node"):
        issues.append("node not found (install Node.js 18+)")

    if not shutil.which("npm"):
        issues.append("npm not found")

    if not shutil.which("python3"):
        issues.append("python3 not found")

    if issues:
        print("  prerequisites missing:")
        for i in issues:
            print(f"    - {i}")
        print()
        return False
    return True


def generate_configs():
    """Generate config files from google-services.json."""
    if not GOOGLE_SERVICES.exists():
        print("  skip config   google-services.json not found")
        return False

    with open(GOOGLE_SERVICES) as f:
        gs = json.load(f)

    pi = gs.get("project_info", {})
    client = gs.get("client", [{}])[0]

    project_id = pi.get("project_id", "")
    db_url = pi.get("firebase_url", "")
    storage = pi.get("storage_bucket", "")
    proj_num = pi.get("project_number", "")
    api_key = ""
    keys = client.get("api_key", [])
    if keys:
        api_key = keys[0].get("current_key", "")

    # ML config
    ML_CONFIG.parent.mkdir(parents=True, exist_ok=True)
    with open(ML_CONFIG, "w") as f:
        json.dump({"databaseURL": db_url, "storageBucket": storage}, f, indent=2)
        f.write("\n")
    print("  config         ml/sentinel_config.json")

    # Web config - preserve existing web-specific values
    web_api_key = api_key
    web_app_id = ""
    web_measurement_id = ""
    auth_domain = f"{project_id}.firebaseapp.com"

    if WEB_ENV.exists():
        with open(WEB_ENV) as f:
            for line in f:
                line = line.strip()
                if "=" not in line:
                    continue
                k, v = line.split("=", 1)
                if k == "NEXT_PUBLIC_FIREBASE_API_KEY" and v:
                    web_api_key = v
                elif k == "NEXT_PUBLIC_FIREBASE_APP_ID" and v:
                    web_app_id = v
                elif k == "NEXT_PUBLIC_FIREBASE_MEASUREMENT_ID" and v:
                    web_measurement_id = v
                elif k == "NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN" and v:
                    auth_domain = v

    with open(WEB_ENV, "w") as f:
        f.write(f"NEXT_PUBLIC_FIREBASE_API_KEY={web_api_key}\n")
        f.write(f"NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN={auth_domain}\n")
        f.write(f"NEXT_PUBLIC_FIREBASE_DATABASE_URL={db_url}\n")
        f.write(f"NEXT_PUBLIC_FIREBASE_PROJECT_ID={project_id}\n")
        f.write(f"NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET={storage}\n")
        f.write(f"NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID={proj_num}\n")
        f.write(f"NEXT_PUBLIC_FIREBASE_APP_ID={web_app_id}\n")
        f.write(f"NEXT_PUBLIC_FIREBASE_MEASUREMENT_ID={web_measurement_id}\n")
    print("  config         web/.env.local")

    if SA_KEY.exists():
        print("  config         ml/serviceAccountKey.json found")
    else:
        print("  warning        ml/serviceAccountKey.json missing")
        print("                 get it from Firebase Console > Service accounts")

    return True


def setup_web():
    """Install web dependencies."""
    lock = WEB_DIR / "node_modules" / ".package-lock.json"
    if lock.exists():
        print("  web            dependencies already installed")
        return True

    print("  web            installing dependencies...")
    result = subprocess.run(
        ["npm", "install"],
        cwd=str(WEB_DIR),
        capture_output=True, text=True, timeout=120
    )
    if result.returncode != 0:
        print(f"  error          npm install failed")
        print(f"                 {result.stderr[:200]}")
        return False
    print("  web            dependencies installed")
    return True


def setup_ml_venv():
    """Create and install ML virtual environment."""
    venv_dir = ML_DIR / "venv"
    pip_path = venv_dir / "bin" / "pip"
    python_path = venv_dir / "bin" / "python"

    if not venv_dir.exists():
        print("  ml             creating virtual environment...")
        subprocess.run([sys.executable, "-m", "venv", str(venv_dir)],
                      capture_output=True, timeout=30)
        print("  ml             venv created")

    # Check if deps already installed
    result = subprocess.run(
        [str(pip_path), "list", "--format=json"],
        capture_output=True, text=True, timeout=15
    )
    installed = set()
    if result.returncode == 0:
        try:
            for pkg in json.loads(result.stdout):
                installed.add(pkg["name"].lower())
        except Exception:
            pass

    needed = {"ultralytics", "face-recognition", "firebase-admin", "customtkinter", "opencv-python", "numpy", "pillow", "requests"}
    missing = needed - installed

    if not missing:
        print("  ml             dependencies already installed")
        return str(python_path)

    print(f"  ml             installing {len(missing)} packages (this takes a while)...")
    result = subprocess.run(
        [str(pip_path), "install", "-r", str(ML_DIR / "requirements.txt")],
        capture_output=True, text=True, timeout=600
    )
    if result.returncode != 0:
        print("  error          pip install failed")
        # Show last 3 lines of error
        lines = result.stderr.strip().split("\n")
        for line in lines[-3:]:
            print(f"                 {line[:100]}")
        return None

    print("  ml             dependencies installed")
    return str(python_path)


def start_web_dev(background=True):
    """Start the Next.js dev server."""
    print("  web            starting dev server on http://localhost:3000")
    if background:
        proc = subprocess.Popen(
            ["npm", "run", "dev"],
            cwd=str(WEB_DIR),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        time.sleep(2)
        if proc.poll() is not None:
            print("  error          dev server failed to start")
            return None
        print(f"  web            running (pid {proc.pid})")
        return proc
    else:
        subprocess.run(["npm", "run", "dev"], cwd=str(WEB_DIR))
        return None


def start_ml(python_path, mode="watch"):
    """Start the ML pipeline."""
    script = str(ML_DIR / "sentinel_ml.py")
    if mode == "gui":
        script = str(ML_DIR / "sentinel_gui.py")

    args = [python_path, script]
    if mode == "once":
        args.append("--once")
    elif mode == "stats":
        args.append("--stats")

    print(f"  ml             starting {'gui' if mode == 'gui' else mode} mode...")
    if mode in ("watch", "gui"):
        proc = subprocess.Popen(args, cwd=str(ML_DIR))
        print(f"  ml             running (pid {proc.pid})")
        return proc
    else:
        subprocess.run(args, cwd=str(ML_DIR))
        return None


def build_android():
    """Build Android APK using Gradle."""
    gradlew = ROOT / "gradlew"
    if not gradlew.exists():
        print("  android        gradlew not found, skip")
        return False

    print("  android        building debug APK (this takes a while)...")
    result = subprocess.run(
        [str(gradlew), "assembleDebug", "--no-daemon"],
        cwd=str(ROOT),
        capture_output=True, text=True, timeout=600
    )
    if result.returncode != 0:
        print("  error          build failed")
        lines = result.stderr.strip().split("\n")
        for line in lines[-3:]:
            print(f"                 {line[:100]}")
        return False

    apk = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    if apk.exists():
        size_mb = apk.stat().st_size / (1024 * 1024)
        print(f"  android        built ({size_mb:.1f} MB)")
        print(f"                 {apk}")
    return True


def main():
    parser = argparse.ArgumentParser(
        description="Door Sentinel - start everything from one command",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
examples:
  python3 run.py                    setup + web + ml watch mode
  python3 run.py --gui              setup + web + desktop gui
  python3 run.py --web-only         just the website
  python3 run.py --ml-only          just the ml pipeline
  python3 run.py --ml-only --once   process all then exit
  python3 run.py --build-apk        also build Android APK
  python3 run.py --setup-only       just generate configs
"""
    )
    parser.add_argument("--web-only", action="store_true", help="only start web dashboard")
    parser.add_argument("--ml-only", action="store_true", help="only start ML pipeline")
    parser.add_argument("--gui", action="store_true", help="use desktop GUI instead of CLI")
    parser.add_argument("--once", action="store_true", help="process all events once then exit")
    parser.add_argument("--stats", action="store_true", help="show ML stats then exit")
    parser.add_argument("--build-apk", action="store_true", help="also build Android debug APK")
    parser.add_argument("--setup-only", action="store_true", help="just generate config files")
    parser.add_argument("--no-web", action="store_true", help="skip web server")
    parser.add_argument("--no-ml", action="store_true", help="skip ML pipeline")
    args = parser.parse_args()

    banner()

    # --- Prerequisites ---
    if not check_prereqs():
        sys.exit(1)

    # --- Generate configs ---
    generate_configs()
    print()

    if args.setup_only:
        print("  done.")
        return

    procs = []

    try:
        # --- Web ---
        if not args.ml_only and not args.no_web:
            if setup_web():
                p = start_web_dev(background=not args.web_only)
                if p:
                    procs.append(p)
                if args.web_only:
                    return
            print()

        # --- ML ---
        if not args.web_only and not args.no_ml:
            python_path = setup_ml_venv()
            if python_path:
                if args.stats:
                    start_ml(python_path, mode="stats")
                    return
                elif args.once:
                    start_ml(python_path, mode="once")
                elif args.gui:
                    p = start_ml(python_path, mode="gui")
                    if p:
                        procs.append(p)
                else:
                    p = start_ml(python_path, mode="watch")
                    if p:
                        procs.append(p)
            print()

        # --- Android ---
        if args.build_apk:
            build_android()
            print()

        # --- Wait ---
        if procs:
            print("  " + "-" * 40)
            print("  all services running. ctrl+c to stop.")
            print()
            for p in procs:
                p.wait()

    except KeyboardInterrupt:
        print("\n  shutting down...")
        for p in procs:
            p.terminate()
        for p in procs:
            try:
                p.wait(timeout=5)
            except subprocess.TimeoutExpired:
                p.kill()
        print("  stopped.")


if __name__ == "__main__":
    main()
