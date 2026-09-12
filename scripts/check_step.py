import urllib.request
import json

req = urllib.request.Request(
    "https://api.github.com/repos/davidhunchoi/todak-todak/actions/runs",
    headers={"User-Agent": "Mozilla/5.0"}
)
with urllib.request.urlopen(req) as resp:
    data = json.loads(resp.read().decode())
    latest_run = data["workflow_runs"][0]
    run_id = latest_run["id"]
    print(f"Latest Run: {run_id} | Status: {latest_run['status']} | Conclusion: {latest_run['conclusion']} | Tag: {latest_run['head_branch']}")

jobs_req = urllib.request.Request(
    f"https://api.github.com/repos/davidhunchoi/todak-todak/actions/runs/{run_id}/jobs",
    headers={"User-Agent": "Mozilla/5.0"}
)
with urllib.request.urlopen(jobs_req) as jresp:
    jdata = json.loads(jresp.read().decode())
    for job in jdata.get("jobs", []):
        print(f"Job: {job['name']} | Status: {job['status']} | Conclusion: {job['conclusion']}")
        for s in job.get("steps", []):
            print(f"  Step: {s['name']} -> {s['status']} ({s['conclusion']})")
