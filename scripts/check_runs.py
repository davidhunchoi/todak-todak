import urllib.request
import json

req = urllib.request.Request(
    "https://api.github.com/repos/davidhunchoi/todak-todak/actions/runs",
    headers={"User-Agent": "Mozilla/5.0"}
)
try:
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode())
        runs = data.get("workflow_runs", [])
        for r in runs[:3]:
            print(f"ID: {r['id']} | {r['name']} | Status: {r['status']} | Conclusion: {r['conclusion']} | Tag/Branch: {r['head_branch']}")
except Exception as e:
    print("Error:", e)
