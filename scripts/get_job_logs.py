import urllib.request
import json

req = urllib.request.Request(
    "https://api.github.com/repos/davidhunchoi/todak-todak/actions/runs/34700258788/jobs",
    headers={"User-Agent": "Mozilla/5.0"}
)
with urllib.request.urlopen(req) as resp:
    data = json.loads(resp.read().decode())
    job_id = data["jobs"][0]["id"]
    print("Job ID:", job_id)

# Download log for this job
log_req = urllib.request.Request(
    f"https://api.github.com/repos/davidhunchoi/todak-todak/actions/jobs/{job_id}/logs",
    headers={"User-Agent": "Mozilla/5.0"}
)
try:
    with urllib.request.urlopen(log_req) as log_resp:
        lines = log_resp.read().decode("utf-8", errors="ignore").splitlines()
        for line in lines[-100:]:
            if "error:" in line.lower() or "failed" in line.lower() or "exception" in line.lower() or "e: " in line:
                print(line)
except Exception as e:
    print("Log fetch error:", e)
