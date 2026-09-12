import urllib.request
import json

req = urllib.request.Request(
    "https://api.github.com/repos/davidhunchoi/todak-todak/actions/runs/34700258788/jobs",
    headers={"User-Agent": "Mozilla/5.0"}
)
try:
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode())
        for job in data.get("jobs", []):
            print(f"Job: {job['name']} | Conclusion: {job['conclusion']}")
            for step in job.get("steps", []):
                if step.get("conclusion") == "failure":
                    print(f"  FAILED STEP: {step['name']} ({step.get('conclusion')})")
except Exception as e:
    print("Error:", e)
