import json
import os
import socket
import subprocess
from datetime import datetime, timedelta

# ---------- PATH SETUP ----------
REPO_PATH = "."  # current directory
JSON_REL_PATH = "coordinates.json"
JSON_PATH = JSON_REL_PATH
HOST = "0.0.0.0"  # listen on all interfaces
PORT = 5000
RATE_LIMIT_SECONDS = 30
# -------------------------------

last_push_time = datetime.min

# ---------- GIT ----------
def git_push(commit_message):
    status = subprocess.run(
        ["git", "status", "--porcelain"],
        cwd=REPO_PATH,
        stdout=subprocess.PIPE,
        text=True
    ).stdout

    if not status.strip():
        print("No changes to push")
        return

    subprocess.run(["git", "add", JSON_REL_PATH], cwd=REPO_PATH, check=True)
    subprocess.run(["git", "commit", "-m", commit_message], cwd=REPO_PATH, check=True)
    subprocess.run(["git", "push"], cwd=REPO_PATH, check=True)

    print("Pushed to GitHub")


# ---------- JSON HELPERS ----------
def load_json_safe(path):
    if not os.path.exists(path) or os.path.getsize(path) == 0:
        return {"entries": []}
    try:
        with open(path, "r") as f:
            return json.load(f)
    except json.JSONDecodeError:
        print("JSON corrupted — resetting")
        return {"entries": []}


def save_json(path, data):
    with open(path, "w") as f:
        json.dump(data, f, indent=2)


# ---------- JSON OPERATIONS ----------
def add_entry(sensor_data):
    data = load_json_safe(JSON_PATH)
    entry = {
        "timestamp": datetime.utcnow().isoformat(),
        "temperature_c": sensor_data.get("temp_c"),
        "tds_ppm": sensor_data.get("tds"),
        "turbidity_v": sensor_data.get("turbidity_v"),
        "ph": sensor_data.get("ph")
    }
    data.setdefault("entries", []).append(entry)
    save_json(JSON_PATH, data)
    git_push("Add sensor data entry")


def remove_last_entry():
    data = load_json_safe(JSON_PATH)
    if not data.get("entries"):
        print("No entries to remove")
        return
    removed = data["entries"].pop()
    save_json(JSON_PATH, data)
    git_push("Remove last JSON entry")
    print("Removed:", removed)


def remove_all_entries():
    data = load_json_safe(JSON_PATH)
    data["entries"] = []
    save_json(JSON_PATH, data)
    git_push("Clear all JSON entries")
    print("All entries removed")


# ---------- SOCKET SERVER ----------
sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.bind((HOST, PORT))
sock.listen(1)
print(f"🌐 Listening on {HOST}:{PORT}")

while True:
    conn, addr = sock.accept()
    with conn:
        print("📥 Connected by", addr)
        try:
            data_bytes = conn.recv(1024)
            if not data_bytes:
                continue
            sensor_data = json.loads(data_bytes.decode())
            now = datetime.utcnow()
            if (now - last_push_time).total_seconds() >= RATE_LIMIT_SECONDS:
                add_entry(sensor_data)
                last_push_time = now
            else:
                print("⏱ Skipped push: waiting for 30 seconds")
            conn.sendall(b"OK")
        except Exception as e:
            print("❌ Error:", e)
            conn.sendall(b"ERROR")
