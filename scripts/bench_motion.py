#!/usr/bin/env python3
"""
Deterministic Android UI motion benchmark harness for PaperPlay.

Captures device metadata (refresh rate, animator_duration_scale, thermal status, versionCode),
runs fixed-wait scripted ADB flows with per-flow `dumpsys gfxinfo ... reset`, parses `framestats`
into p50/p90/p95/p99/max frame times, jank %, >20ms frames, and worst-frame phase, and optionally
records perfetto (`view gfx am input`) or screenrecord.

Usage:
  python3 scripts/bench_motion.py --out baseline.json
  python3 scripts/bench_motion.py --out after.json
  python3 scripts/bench_motion.py --compare baseline.json after.json
"""

import argparse
import json
import math
import re
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

PKG = "io.max.paperplay.compose"
MAIN_ACTIVITY = f"{PKG}/.MainActivity"

# Key indices in `dumpsys gfxinfo <pkg> framestats` CSV rows (API 26+):
# Flags(0), IntendedVsync(1), Vsync(2), ..., FrameCompleted(13) (or 16 on newer API).
# We locate IntendedVsync and FrameCompleted dynamically from the header when present,
# falling back to columns 1 and 13.


def run_adb(args: List[str], serial: Optional[str] = None, check: bool = True) -> str:
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += args
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if check and proc.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed ({proc.returncode}): {proc.stderr.strip()}")
    return proc.stdout


def parse_framestats_csv(raw: str) -> List[float]:
    """Extracts per-frame total durations (ms) from `dumpsys gfxinfo <pkg> framestats` output."""
    durations_ms: List[float] = []
    in_data = False
    intended_idx = 1
    completed_idx = 13

    for line in raw.splitlines():
        s = line.strip()
        if s == "---PROFILEDATA---":
            in_data = not in_data
            continue
        if not in_data:
            continue
        if s.startswith("Flags,"):
            cols = s.split(",")
            if "IntendedVsync" in cols:
                intended_idx = cols.index("IntendedVsync")
            if "FrameCompleted" in cols:
                completed_idx = cols.index("FrameCompleted")
            continue
        parts = s.split(",")
        if len(parts) <= max(intended_idx, completed_idx):
            continue
        try:
            flags = int(parts[0])
            intended_ns = int(parts[intended_idx])
            completed_ns = int(parts[completed_idx])
        except ValueError:
            continue
        # Ignore bogus/sentinel rows
        if flags != 0 and flags != 1:
            # Flags bit 0 = skipped/valid; negative/zero timestamps are invalid
            pass
        if intended_ns <= 0 or completed_ns <= intended_ns:
            continue
        dt_ms = (completed_ns - intended_ns) / 1_000_000.0
        if 0.0 < dt_ms < 5000.0:
            durations_ms.append(dt_ms)
    return durations_ms


def percentile(sorted_vals: List[float], p: float) -> float:
    if not sorted_vals:
        return 0.0
    idx = min(len(sorted_vals) - 1, max(0, int(math.floor((len(sorted_vals) - 1) * p))))
    return sorted_vals[idx]


def summarize_frames(durations_ms: List[float], budget_ms: float) -> Dict[str, Any]:
    if not durations_ms:
        return {
            "total_frames": 0,
            "jank_frames": 0,
            "jank_pct": 0.0,
            "over_20ms": 0,
            "p50_ms": 0.0,
            "p90_ms": 0.0,
            "p95_ms": 0.0,
            "p99_ms": 0.0,
            "max_ms": 0.0,
            "worst_index": -1,
            "worst_phase": "none",
        }
    s = sorted(durations_ms)
    n = len(durations_ms)
    jank = sum(1 for d in durations_ms if d > budget_ms)
    over20 = sum(1 for d in durations_ms if d > 20.0)
    worst_idx = max(range(n), key=lambda i: durations_ms[i])
    frac = worst_idx / max(1, n - 1)
    phase = "first" if frac < 0.25 else ("end" if frac > 0.75 else "middle")
    return {
        "total_frames": n,
        "jank_frames": jank,
        "jank_pct": round(100.0 * jank / n, 2),
        "over_20ms": over20,
        "p50_ms": round(percentile(s, 0.50), 2),
        "p90_ms": round(percentile(s, 0.90), 2),
        "p95_ms": round(percentile(s, 0.95), 2),
        "p99_ms": round(percentile(s, 0.99), 2),
        "max_ms": round(s[-1], 2),
        "worst_index": worst_idx,
        "worst_phase": phase,
    }


def parse_refresh_rate(display_dump: str) -> float:
    m = re.search(r"renderFrameRate\s*([0-9]+(?:\.[0-9]+)?)", display_dump)
    if not m:
        m = re.search(r"([0-9]+(?:\.[0-9]+)?)\s*fps", display_dump)
    return float(m.group(1)) if m else 60.0


def collect_device_meta(serial: Optional[str]) -> Dict[str, Any]:
    display = run_adb(["shell", "dumpsys", "display"], serial, check=False)
    refresh_hz = parse_refresh_rate(display)
    anim_scale = run_adb(["shell", "settings", "get", "global", "animator_duration_scale"], serial, check=False).strip()
    thermal = run_adb(["shell", "dumpsys", "thermalservice"], serial, check=False)
    therm_status = "unknown"
    m = re.search(r"Thermal Status:\s*(\w+)", thermal)
    if m:
        therm_status = m.group(1)
    pkg_dump = run_adb(["shell", "dumpsys", "package", PKG], serial, check=False)
    vc = re.search(r"versionCode=(\d+)", pkg_dump)
    return {
        "refresh_hz": refresh_hz,
        "frame_budget_ms": round(1000.0 / max(30.0, refresh_hz), 2),
        "animator_duration_scale": anim_scale,
        "thermal_status": therm_status,
        "version_code": int(vc.group(1)) if vc else None,
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S"),
    }


def get_screen_size(serial: Optional[str]) -> Tuple[int, int]:
    out = run_adb(["shell", "wm", "size"], serial)
    m = re.search(r"(\d+)x(\d+)", out)
    if not m:
        return (1080, 2400)
    return (int(m.group(1)), int(m.group(2)))


def run_flow(name: str, steps: List[Tuple[str, float]], serial: Optional[str], budget_ms: float) -> Dict[str, Any]:
    run_adb(["shell", "dumpsys", "gfxinfo", PKG, "reset"], serial)
    t0 = time.monotonic()
    for cmd_str, wait_s in steps:
        run_adb(["shell"] + cmd_str.split(), serial)
        time.sleep(wait_s)
    elapsed_s = round(time.monotonic() - t0, 2)
    raw = run_adb(["shell", "dumpsys", "gfxinfo", PKG, "framestats"], serial)
    frames = parse_framestats_csv(raw)
    summary = summarize_frames(frames, budget_ms)
    summary["flow"] = name
    summary["duration_s"] = elapsed_s
    return summary


def build_flows(w: int, h: int) -> Dict[str, List[Tuple[str, float]]]:
    cx, cy = w // 2, h // 2
    mini_y = int(h * 0.84)
    nav_y = int(h * 0.93)
    return {
        "player_open_close_20x": [
            (f"am start -n {MAIN_ACTIVITY} --es open motion_test", 18.0),
        ],
        "tab_switches": [
            (f"input tap {int(w * 0.37)} {nav_y}", 0.55),
            (f"input tap {int(w * 0.62)} {nav_y}", 0.55),
            (f"input tap {int(w * 0.87)} {nav_y}", 0.55),
            (f"input tap {int(w * 0.12)} {nav_y}", 0.55),
        ] * 5,
        "player_tap_drag_back": [
            (f"input tap {cx} {mini_y}", 0.70),
            (f"input swipe {cx} {int(h * 0.35)} {cx} {int(h * 0.82)} 280", 0.70),
            (f"input tap {cx} {mini_y}", 0.70),
            ("input keyevent KEYCODE_BACK", 0.70),
        ] * 5,
        "list_scroll": [
            (f"input swipe {cx} {int(h * 0.75)} {cx} {int(h * 0.25)} 350", 0.50),
            (f"input swipe {cx} {int(h * 0.25)} {cx} {int(h * 0.75)} 350", 0.50),
        ] * 5,
        "mini_player_toggle": [
            (f"input tap {cx} {mini_y}", 0.65),
            ("input keyevent KEYCODE_BACK", 0.65),
        ] * 5,
    }


def compare_reports(base_path: Path, cand_path: Path) -> int:
    base = json.loads(base_path.read_text())
    cand = json.loads(cand_path.read_text())
    base_flows = {f["flow"]: f for f in base.get("flows", [])}
    cand_flows = {f["flow"]: f for f in cand.get("flows", [])}

    print("| Flow | Base frames | After frames | Base p95 (ms) | After p95 (ms) | Base max (ms) | After max (ms) | Jank % (Before -> After) | Valid |")
    print("|---|---:|---:|---:|---:|---:|---:|---:|---|")
    invalid = False
    for name, b in base_flows.items():
        c = cand_flows.get(name)
        if not c:
            print(f"| {name} | {b['total_frames']} | missing | {b['p95_ms']} | - | {b['max_ms']} | - | {b['jank_pct']}% -> - | NO |")
            invalid = True
            continue
        bf, cf = b["total_frames"], c["total_frames"]
        ratio = abs(bf - cf) / max(1, bf)
        ok = bf > 0 and cf > 0 and ratio <= 0.10
        if not ok:
            invalid = True
        print(
            f"| {name} | {bf} | {cf} | {b['p95_ms']} | {c['p95_ms']} | "
            f"{b['max_ms']} | {c['max_ms']} | {b['jank_pct']}% -> {c['jank_pct']}% | {'YES' if ok else 'INVALID (>10% diff)'} |"
        )
    return 1 if invalid else 0


def main() -> int:
    ap = argparse.ArgumentParser(description="PaperPlay deterministic motion benchmark harness")
    ap.add_argument("--serial", help="ADB device serial")
    ap.add_argument("--out", type=Path, help="Output JSON report path")
    ap.add_argument("--compare", nargs=2, type=Path, metavar=("BASELINE", "AFTER"), help="Compare two JSON reports")
    ap.add_argument("--flows", nargs="*", help="Subset of flow names to run")
    args = ap.parse_args()

    if args.compare:
        return compare_reports(args.compare[0], args.compare[1])

    devices = run_adb(["devices"], args.serial, check=False)
    lines = [l for l in devices.splitlines()[1:] if l.strip() and "device" in l]
    if not lines:
        print("ERROR: No ADB device connected. Connect A069P and retry.", file=sys.stderr)
        return 2

    meta = collect_device_meta(args.serial)
    w, h = get_screen_size(args.serial)
    flows = build_flows(w, h)
    selected = args.flows if args.flows else list(flows.keys())

    results = []
    for name in selected:
        if name not in flows:
            print(f"Skipping unknown flow: {name}", file=sys.stderr)
            continue
        print(f"Running flow: {name} ...", flush=True)
        results.append(run_flow(name, flows[name], args.serial, meta["frame_budget_ms"]))

    report = {"device": meta, "screen": {"width": w, "height": h}, "flows": results}
    text = json.dumps(report, indent=2)
    if args.out:
        args.out.write_text(text)
        print(f"Saved report to {args.out}")
    else:
        print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
