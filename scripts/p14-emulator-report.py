"""Summarize real adb instrumentation output without claiming physical acceptance."""
import json
import re
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

root = Path("evidence")
suite = ET.Element("testsuite", name="P14 Task4 emulator instrumentation")
results = []
leaks = []
for path in sorted(root.glob("emulator-*.txt")):
    text = path.read_text(errors="replace")
    if "INSTRUMENTATION_" not in text and "OK (" not in text:
        continue
    passed = bool(re.search(r"OK \([1-9][0-9]* test", text)) and not any(
        marker in text for marker in ("FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed")
    )
    result = {"evidence": path.name, "result": "PASS" if passed else "FAIL"}
    results.append(result)
    case = ET.SubElement(suite, "testcase", classname="P14.AndroidEmulator", name=path.stem)
    if not passed:
        ET.SubElement(case, "failure", message="Instrumented test did not pass").text = re.sub(r"p14d_[A-Za-z0-9_-]{30,}", "[REDACTED_DEVICE_CREDENTIAL]", text[-4000:])
for path in root.rglob("*"):
    if path.suffix not in (".txt", ".log", ".xml", ".json") or not path.is_file():
        continue
    text = path.read_text(errors="replace")
    values = re.findall(r"p14d_[A-Za-z0-9_-]{30,}", text)
    if values:
        leaks.append({"path": str(path.relative_to(root)), "count": len(values)})
        path.write_text(re.sub(r"p14d_[A-Za-z0-9_-]{30,}", "[REDACTED_DEVICE_CREDENTIAL]", text))
suite.set("tests", str(len(results)))
suite.set("failures", str(sum(r["result"] != "PASS" for r in results)))
ET.ElementTree(suite).write(root / "instrumentation-junit.xml", encoding="utf-8", xml_declaration=True)
(root / "automated-summary.json").write_text(json.dumps({
    "layer": "synthetic Android emulator", "physical_device_verified": False,
    "hermes_invoked": False, "instrumentation": results,
    "credential_log_leaks": leaks, "complete_task4_acceptance": False,
}, indent=2) + "\n")
if leaks:
    raise SystemExit("Device credential appeared in diagnostic output; evidence redacted, security check FAILED")
if not results:
    raise SystemExit("No instrumentation execution evidence found")

