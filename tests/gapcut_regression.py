#!/usr/bin/env python3
"""gapcut 진단의 과거 회차 재현성 회귀 테스트."""

import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CP = ROOT / "out" / "production" / "mskim"


def run(*args):
    proc = subprocess.run(
        ["java", "-cp", str(CP), "LottoPatternAnalyzer", *args],
        cwd=ROOT,
        capture_output=True,
        text=True,
        timeout=900,
    )
    if proc.returncode != 0:
        raise AssertionError(proc.stderr or proc.stdout)
    return proc.stdout


def assert_gapcut_uses_requested_hi_as_prediction_base():
    out = run("gapcut", "1220", "1229")
    header = re.search(r"##GAPCUT## 대상 1220~1229, 창 \d+회, 예측회차 (\d+)", out)
    assert header, "gapcut header not found"
    assert header.group(1) == "1230", (
        "gapcut must predict hi+1 for historical ranges; "
        f"got {header.group(1)}"
    )
    assert "1230@@" in out, "live prediction row must be for hi+1"
    assert "1231@@" not in out, "gapcut must not use the latest CSV draw as the live row"


def main():
    assert_gapcut_uses_requested_hi_as_prediction_base()
    print("gapcut regression ok")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        sys.exit(1)
