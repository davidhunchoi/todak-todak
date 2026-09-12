import urllib.request
import json

req = urllib.request.Request(
    "https://api.github.com/repos/davidhunchoi/todak-todak/actions/runs/34700552845/jobs",
    headers={"User-Agent": "Mozilla/5.0"}
)
try:
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode())
        for job in data.get("jobs", []):
            print(f"Job: {job['name']} | Status: {job['status']} | Conclusion: {job['conclusion']}")
            for s in job.get("steps", []):
                print(f"  Step: {s['name']} -> {s['status']} ({s['conclusion']})")
except Exception as e:
    print("Error:", e)
