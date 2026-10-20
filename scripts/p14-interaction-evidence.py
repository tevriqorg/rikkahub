"""Read only Runner synthetic PostgreSQL proof for actual Android Interactions."""
import hashlib
import json
import os
import sys
import uuid
from pathlib import Path
import psycopg

assert os.environ.get("GITHUB_ACTIONS") == "true"
run_id = sys.argv[1]
assert run_id.replace("-", "").replace("_", "").isalnum()
def identity(kind):
    digest = hashlib.md5(f"task4-{run_id}-{kind}-interaction".encode()).digest()
    return str(uuid.UUID(bytes=digest, version=3))
results = {}
with psycopg.connect("host=127.0.0.1 port=5432 dbname=p14_task4 user=p14 password=synthetic-ci-only") as db:
    for kind, expected in (("race", 1), ("direct", 1), ("expiry", 0)):
        key = identity(kind)
        row = db.execute("SELECT status,response_text FROM agent_interactions WHERE interaction_id=%s", (key,)).fetchone()
        assert row is not None, kind
        events = db.execute("SELECT caused_by_operation_id FROM sync_events WHERE entity_id=%s AND event_type='agent.interaction.responded'", (key,)).fetchall()
        assert len(events) == expected, (kind, len(events))
        assert row[0] == ("RESPONDED" if expected else "PENDING"), (kind, row[0])
        idempotency = db.execute("SELECT response_json FROM idempotency_results").fetchall()
        matching = [json.loads(item[0]) for item in idempotency if json.loads(item[0]).get("interaction_id") == key]
        assert len(matching) == expected, (kind, len(matching))
        if expected:
            assert matching[0]["client_operation_id"] == events[0][0]
        results[kind] = {"interaction_id": key, "status": row[0], "response_events": len(events), "idempotency_results": len(matching)}
Path("evidence/android-interaction-server-proof.json").write_text(json.dumps(results, indent=2) + "\n")
print("Android Interaction proof PASS: race/direct once each, expired zero")
