#!/usr/bin/env python3
import argparse, json, os, pathlib, re, subprocess, sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
LOCK = ROOT / "docs" / "P14_RECOVERY_AUTHORITY.lock.json"
ROW_ID = re.compile(r"^[A-Z0-9]+(?:-[A-Z0-9]+)+$")

def fail(msg):
    print(f"P14 Android recovery guard: {msg}", file=sys.stderr)
    raise SystemExit(1)

def load_lock():
    data=json.loads(LOCK.read_text(encoding="utf-8"))
    if data.get("schema") != "p14.recovery-authority-lock/v1": fail("unexpected lock schema")
    if data.get("authority_issue") != "tevriqorg/P14-Entry#51": fail("authority issue drifted")
    if not data.get("scope_revision"): fail("scope_revision missing")
    rows=data.get("valid_row_ids")
    if not isinstance(rows,list) or not rows: fail("valid_row_ids missing")
    ids=set(rows)
    if len(ids)!=len(rows): fail("duplicate row id in lock")
    if any(not ROW_ID.match(x) for x in ids): fail("invalid row id in lock")
    return data,ids

def validate_docs():
    agents=(ROOT/"AGENTS.md").read_text(encoding="utf-8")
    gov=(ROOT/"docs"/"P14_REMOTE_GOVERNANCE.md").read_text(encoding="utf-8")
    for name,text in [("AGENTS.md",agents),("docs/P14_REMOTE_GOVERNANCE.md",gov)]:
        if "tevriqorg/P14-Entry#51" not in text: fail(f"{name} lost #51 authority reference")
        if "P14-Row:" not in text: fail(f"{name} lost commit row rule")

def validate_commits(data,ids,head):
    base=data.get("commit_enforcement_after")
    if not base: fail("commit enforcement base missing")
    commits=subprocess.check_output(["git","rev-list","--reverse",f"{base}..{head}"],text=True).splitlines()
    for commit in commits:
        body=subprocess.check_output(["git","show","-s","--format=%B",commit],text=True)
        m=re.search(r"(?mi)^P14-Row:\s*(.+)$",body)
        if not m: fail(f"commit {commit[:12]} lacks P14-Row trailer")
        declared={x.strip() for x in m.group(1).split(",") if x.strip()}
        unknown=sorted(declared-ids)
        if unknown: fail(f"commit {commit[:12]} references unknown row(s): {', '.join(unknown)}")

def validate_pr(body,ids):
    m=re.search(r"(?mi)^P14-Recovery-Rows:\s*(.+)$",body or "")
    if not m: fail("PR body lacks P14-Recovery-Rows")
    declared={x.strip() for x in m.group(1).split(",") if x.strip()}
    unknown=sorted(declared-ids)
    if unknown: fail("PR references unknown row(s): "+", ".join(unknown))
    scope=re.search(r"(?mi)^P14-Scope-Change:\s*(.+)$",body or "")
    if not scope or scope.group(1).strip().lower()!="no":
        fail("P14-Scope-Change must be 'no' for the frozen recovery")
    if not re.search(r"(?mi)^P14-Acceptance-Evidence:\s*(.+)$",body or ""):
        fail("PR body lacks P14-Acceptance-Evidence")

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--head",default="HEAD")
    ap.add_argument("--pr-body-env")
    args=ap.parse_args()
    data,ids=load_lock()
    validate_docs()
    validate_commits(data,ids,args.head)
    if args.pr_body_env: validate_pr(os.environ.get(args.pr_body_env,""),ids)
    print(f"P14 Android recovery guard OK: {len(ids)} rows, scope={data['scope_revision']}, authority={data['authority_commit'][:12]}")
if __name__=="__main__": main()
