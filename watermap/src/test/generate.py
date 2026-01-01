import json
import random
from datetime import datetime, timedelta
import os

# Path to the JSON file
output_path = "watermap/src/main/resources/static/data/union_lake_test.json"

# Delete existing file if it exists
if os.path.exists(output_path):
    os.remove(output_path)

entries = []
start_time = datetime(2026, 1, 1, 0, 0, 0)

for i in range(10):
    entry_time = start_time + timedelta(minutes=30*i)
    entry = {
        "timestamp": entry_time.isoformat() + "Z",
        "temperature_c": round(random.uniform(5, 30), 1),
        "tds": random.randint(100, 1000),
        "turbidity_v": round(random.uniform(0.5, 10), 1),
        "ph": round(random.uniform(6.5, 10), 1),
        "lat": round(random.uniform(39.400056, 39.440079), 5),
        "lon": round(random.uniform(-75.070843, -75.046240), 5)
    }
    entries.append(entry)

# Write JSON to the specified path
with open(output_path, "w") as f:
    json.dump({"entries": entries}, f, indent=2)

print(f"Generated {len(entries)} entries in {output_path}")
