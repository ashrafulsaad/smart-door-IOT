#!/usr/bin/env python3
"""
Door Sentinel - Project Setup

Reads app/google-services.json and generates all required config files.
"""

import json
import sys
import argparse
from pathlib import Path

ROOT = Path(__file__).parent
GOOGLE_SERVICES = ROOT / "app" / "google-services.json"
ML_CONFIG = ROOT / "ml" / "sentinel_config.json"
WEB_ENV = ROOT / "web" / ".env.local"


def main():
    print()
    print("  Door Sentinel")
    print("  Project Setup")
    print("  " + "-" * 40)
    print()

    # --- Read google-services.json ---
    if not GOOGLE_SERVICES.exists():
        print("  error: app/google-services.json not found")
        print("  download from Firebase Console > Project Settings > Your Apps")
        sys.exit(1)

    with open(GOOGLE_SERVICES) as f:
        gs = json.load(f)

    project_info = gs.get("project_info", {})
    client = gs.get("client", [{}])[0]

    project_id = project_info.get("project_id", "")
    db_url = project_info.get("firebase_url", "")
    storage_bucket = project_info.get("storage_bucket", "")
    project_number = project_info.get("project_number", "")

    api_keys = client.get("api_key", [])
    api_key = api_keys[0].get("current_key", "") if api_keys else ""
    app_id = client.get("client_info", {}).get("mobilesdk_app_id", "")

    print(f"  project        {project_id}")
    print(f"  database       {db_url[:50]}...")
    print(f"  storage        {storage_bucket}")
    print()

    # --- Generate ml/sentinel_config.json ---
    ml_config = {
        "databaseURL": db_url,
        "storageBucket": storage_bucket,
    }
    ML_CONFIG.parent.mkdir(parents=True, exist_ok=True)
    with open(ML_CONFIG, "w") as f:
        json.dump(ml_config, f, indent=2)
        f.write("\n")
    print("  created        ml/sentinel_config.json")

    # --- Generate web/.env.local ---
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
                key, val = line.split("=", 1)
                if key == "NEXT_PUBLIC_FIREBASE_API_KEY" and val:
                    web_api_key = val
                elif key == "NEXT_PUBLIC_FIREBASE_APP_ID" and val:
                    web_app_id = val
                elif key == "NEXT_PUBLIC_FIREBASE_MEASUREMENT_ID" and val:
                    web_measurement_id = val
                elif key == "NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN" and val:
                    auth_domain = val

    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument("--web-app-id", default="")
    parser.add_argument("--web-api-key", default="")
    parser.add_argument("--measurement-id", default="")
    args, _ = parser.parse_known_args()
    if args.web_app_id:
        web_app_id = args.web_app_id
    if args.web_api_key:
        web_api_key = args.web_api_key
    if args.measurement_id:
        web_measurement_id = args.measurement_id

    env_content = (
        f"NEXT_PUBLIC_FIREBASE_API_KEY={web_api_key}\n"
        f"NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN={auth_domain}\n"
        f"NEXT_PUBLIC_FIREBASE_DATABASE_URL={db_url}\n"
        f"NEXT_PUBLIC_FIREBASE_PROJECT_ID={project_id}\n"
        f"NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET={storage_bucket}\n"
        f"NEXT_PUBLIC_FIREBASE_MESSAGING_SENDER_ID={project_number}\n"
        f"NEXT_PUBLIC_FIREBASE_APP_ID={web_app_id}\n"
        f"NEXT_PUBLIC_FIREBASE_MEASUREMENT_ID={web_measurement_id}\n"
    )
    WEB_ENV.parent.mkdir(parents=True, exist_ok=True)
    with open(WEB_ENV, "w") as f:
        f.write(env_content)
    print("  created        web/.env.local")

    # --- Check service account key ---
    sa_key = ROOT / "ml" / "serviceAccountKey.json"
    if sa_key.exists():
        print("  found          ml/serviceAccountKey.json")
    else:
        print("  missing        ml/serviceAccountKey.json")
        print("                 download from Firebase Console > Service accounts > Generate new private key")

    print()
    print("  setup complete. all config files are gitignored.")
    print()


if __name__ == "__main__":
    main()
