#!/usr/bin/env python3
"""
Wireless-ADB Screen-Off USB Exclusive Audio Soak Harness for PaperPlay.

Runs scripted 15-minute (or custom duration) soaks across:
- Formats: 96 kHz / 24-bit and 44.1 kHz / 16-bit
- Sources: local FLAC (looped) and Telegram track (active library playback with REPEAT_MODE_ONE)
- Modes: screen-on vs screen-off (KEYCODE_SLEEP)
- Labels: before (Phase A baseline) vs after (Phase B fixes), plus optional ADPF/keepalive toggles

Parses TAG_JITTER_STATS, TAG_URB_PACKET_ERR, TAG_PERIODIC_STATS, TAG_THREAD_SCHED,
TAG_CUSHION_ESCALATE, and POWER_TELEMETRY from the exported debug log and outputs a
structured Markdown report with the automated root-cause decision rule:
- packet errors/shorts with low callback gaps (<= 15 ms) -> BUS/VBUS
- large callback gaps (> 15 ms) or >= 6 slots/wakeup (with ring min > 0) -> CPU_SCHEDULING
- ring min == 0 with normal gaps and 0 packet errors -> PRODUCER/NETWORK
- 0 underruns, 0 packet errors, 0 reconfigures, 0 fallbacks -> CLEAN_PASS

Hearing safety:
- Strictly enforces STREAM_MUSIC <= 40/160 before every run; never raises volume.
- Always pauses playback and restores `svc power stayon false` on exit.
"""

import argparse
import dataclasses
import os
import re
import subprocess
import sys
import time
from typing import List, Optional, Tuple

PKG = "io.max.paperplay.compose"
ACTION = "io.max.paperplay.DEV_USB_CMD"
TEST_DIR = os.path.join("scripts", "test_audio")
DEVICE_AUDIO_DIR = f"/sdcard/Android/data/{PKG}/files/test_audio"
SOAK_OUT_DIR = os.path.join("scratch", "soak_reports")

TRACK_16_44 = "test_sine_ramp_16bit_44k.flac"
TRACK_24_96 = "test_sine_ramp_24bit_96k.flac"

RE_JITTER = re.compile(
    r"JITTER_STATS:\s*max_cb_gap_us=(\d+)\s+late_cb_15ms=(\d+)\s+max_slots_wakeup=(\d+)\s+"
    r"min_ring_fill=(\d+)\s+slots_in_flight=(\d+)\s+cpu_mask=(0x[0-9a-fA-F]+)\s+last_cpu=(-?\d+)"
)
RE_PERIODIC = re.compile(
    r"PERIODIC_STATS:\s*underruns=(\d+)\s+ring_fill_bytes=(\d+)"
    r"(?:\s+pkt_errs=(\d+)\s+pkt_shorts=(\d+)\s+last_pkt_err=(-?\d+))?"
)
RE_PACKET_ERR = re.compile(
    r"URB_PACKET_ERR:\s*status=(-?\d+)(?:\s+top_status=(-?\d+)\s+slot=(\d+)\s+pkt_idx=(\d+)\s+"
    r"pkt_errs=(\d+)\s+pkt_shorts=(\d+))?"
)
RE_SCHED_DETAIL = re.compile(
    r"THREAD_SCHED_DETAIL:\s*tid=(\d+)\s+setprio_rc=(-?\d+)\s+setprio_errno=(-?\d+)"
    r"(?:\s+fifo_rc=(-?\d+)\s+fifo_errno=(-?\d+)\s+jni_prio_rc=(-?\d+)\s+adpf=(true|false))?"
    r"\s+nice=(-?\d+)\s+policy=(-?\d+)\s+cpu=(-?\d+)\s+cgroup=(.+)"
)
RE_ESCALATE = re.compile(
    r"CUSHION_ESCALATE:\s*reason=(\S+)\s+old_slots=(\d+)\s+new_slots=(\d+)\s+trigger_val=(-?\d+)"
)


@dataclasses.dataclass
class ParsedTelemetry:
    samples_jitter: int = 0
    max_cb_gap_us: int = 0
    total_late_cb_15ms: int = 0
    max_slots_wakeup: int = 0
    min_ring_fill_bytes: Optional[int] = None
    last_slots_in_flight: int = 0
    cpu_mask_union: int = 0
    underruns: int = 0
    pkt_errs: int = 0
    pkt_shorts: int = 0
    last_pkt_err: int = 0
    packet_err_events: int = 0
    cushion_escalations: int = 0
    reconfigures: int = 0
    fallbacks: int = 0
    nice: str = "?"
    policy: str = "?"
    fifo_errno: str = "?"
    jni_prio_rc: str = "?"
    adpf: str = "?"
    cgroup: str = "?"
    doze_entered: bool = False
    thermal_max: str = "NONE"


def parse_telemetry_report(text: str) -> ParsedTelemetry:
    t = ParsedTelemetry()
    for line in text.splitlines():
        m = RE_JITTER.search(line)
        if m:
            t.samples_jitter += 1
            gap = int(m.group(1))
            late = int(m.group(2))
            max_sw = int(m.group(3))
            min_fill = int(m.group(4))
            in_flight = int(m.group(5))
            cpu_mask = int(m.group(6), 16)
            t.max_cb_gap_us = max(t.max_cb_gap_us, gap)
            t.total_late_cb_15ms += late
            t.max_slots_wakeup = max(t.max_slots_wakeup, max_sw)
            t.min_ring_fill_bytes = min_fill if t.min_ring_fill_bytes is None else min(t.min_ring_fill_bytes, min_fill)
            t.last_slots_in_flight = in_flight
            t.cpu_mask_union |= cpu_mask
            continue

        m = RE_PERIODIC.search(line)
        if m:
            t.underruns = max(t.underruns, int(m.group(1)))
            if m.group(3) is not None:
                t.pkt_errs = max(t.pkt_errs, int(m.group(3)))
                t.pkt_shorts = max(t.pkt_shorts, int(m.group(4)))
                last_e = int(m.group(5))
                if last_e != 0:
                    t.last_pkt_err = last_e
            continue

        m = RE_PACKET_ERR.search(line)
        if m:
            t.packet_err_events += 1
            st = int(m.group(1))
            if st != 0:
                t.last_pkt_err = st
            continue

        m = RE_SCHED_DETAIL.search(line)
        if m:
            if m.group(4) is not None:
                t.fifo_errno = m.group(5)
                t.jni_prio_rc = m.group(6)
                t.adpf = m.group(7)
            t.nice = m.group(8)
            t.policy = m.group(9)
            t.cgroup = m.group(11).strip()
            continue

        if RE_ESCALATE.search(line):
            t.cushion_escalations += 1
            continue

        if "UnifiedUsbAudioSink fallback:" in line or "switchToDelegateFallback:" in line:
            t.fallbacks += 1
        if "USB reconfigure recovery" in line or "realFaultReconfigures=" in line:
            rm = re.search(r"realFaultReconfigures=(\d+)", line)
            if rm:
                t.reconfigures = max(t.reconfigures, int(rm.group(1)))
            elif "USB reconfigure recovery" in line:
                t.reconfigures += 1
        if "doze=true" in line:
            t.doze_entered = True
        tm = re.search(r"thermal=([A-Z]+)\(\d+\)", line)
        if tm and tm.group(1) != "NONE":
            t.thermal_max = tm.group(1)
    return t


def classify_verdict(t: ParsedTelemetry) -> str:
    """
    Decision rule:
    - packet errors/shorts with low gaps (<= 15 ms) => BUS/VBUS
    - large gaps (> 15 ms) or >= 6 slots per wakeup (with ring min > 0) => CPU_SCHEDULING
    - ring min == 0 with normal gaps and 0 packet errors => PRODUCER/NETWORK
    - 0 underruns, 0 packet errors, 0 reconfigures, 0 fallbacks => CLEAN_PASS
    """
    has_pkt_issue = (t.pkt_errs > 0) or (t.pkt_shorts > 0) or (t.packet_err_events > 0)
    has_sched_issue = (t.max_cb_gap_us > 15_000) or (t.total_late_cb_15ms > 0) or (t.max_slots_wakeup >= 6)
    ring_empty = (t.min_ring_fill_bytes is not None) and (t.min_ring_fill_bytes == 0)

    if has_pkt_issue and not has_sched_issue:
        return "BUS/VBUS"
    if has_sched_issue and (not ring_empty or t.underruns == 0):
        return "CPU_SCHEDULING" if not has_pkt_issue else "CPU_SCHEDULING+BUS/VBUS"
    if ring_empty and not has_sched_issue and not has_pkt_issue:
        return "PRODUCER/NETWORK"
    if t.underruns == 0 and not has_pkt_issue and t.reconfigures == 0 and t.fallbacks == 0:
        return "CLEAN_PASS"
    if has_pkt_issue and has_sched_issue:
        return "MIXED(CPU+BUS)"
    return "PRODUCER/NETWORK" if t.underruns > 0 else "WARN"


def adb(serial: str, *args: str, check: bool = False) -> subprocess.CompletedProcess:
    return subprocess.run(["adb", "-s", serial, *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=check)


def dev_cmd(serial: str, cmd: str, extras: Optional[List[str]] = None):
    args = [
        "shell", "am", "broadcast", "-a", ACTION,
        "-n", f"{PKG}/io.max.paperplay.player.usb.UsbDevTestReceiver",
        "--es", "cmd", cmd,
    ]
    if extras:
        args.extend(extras)
    return adb(serial, *args)


def ensure_safe_volume(serial: str, max_safe: int = 40) -> Tuple[int, int]:
    def read_levels():
        txt = adb(serial, "shell", "dumpsys audio | grep -A 8 '^- STREAM_MUSIC:'").stdout
        max_m = re.search(r"Max:\s*(\d+)", txt)
        max_v = int(max_m.group(1)) if max_m else 160
        levels = {m.group(1): int(m.group(2)) for m in re.finditer(r"\((\w+)\):\s*(\d+)", txt)}
        return max_v, levels

    max_v, levels = read_levels()
    usb_idx = levels.get("usb_headset", levels.get("usb_device", 0))
    stalled = 0
    while usb_idx > max_safe:
        print(f"[SAFETY] Lowering STREAM_MUSIC usb_headset {usb_idx}/{max_v} -> <= {max_safe}/{max_v}")
        adb(serial, "shell", "input", "keyevent", "25")
        time.sleep(0.25)
        _, levels = read_levels()
        new_idx = levels.get("usb_headset", levels.get("usb_device", 0))
        stalled = 0 if new_idx < usb_idx else stalled + 1
        if stalled >= 3:
            break
        usb_idx = new_idx
    if usb_idx > max_safe:
        raise RuntimeError(f"Refusing to run: STREAM_MUSIC usb_headset={usb_idx}/{max_v} > {max_safe}")
    print(f"[SAFETY] STREAM_MUSIC usb_headset={usb_idx}/{max_v} (safe <= {max_safe})")
    return usb_idx, max_v


def read_status(serial: str) -> dict:
    dev_cmd(serial, "status")
    time.sleep(0.35)
    txt = adb(serial, "shell", f"run-as {PKG} cat cache/usb_dev_status.txt").stdout
    out = {}
    for line in txt.splitlines():
        if "=" in line and not line.startswith("---"):
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out


def export_full_report(serial: str, save_path: str) -> str:
    dev_cmd(serial, "export_debug_log")
    time.sleep(1.5)
    txt = adb(serial, "shell", f"run-as {PKG} cat cache/usb_dev_full_report.txt").stdout
    os.makedirs(os.path.dirname(save_path), exist_ok=True)
    with open(save_path, "w", encoding="utf-8") as f:
        f.write(txt)
    return txt


def get_usb_thread_count(serial: str) -> int:
    pid = adb(serial, "shell", f"pidof {PKG}").stdout.strip()
    if not pid:
        return 0
    c = adb(serial, "shell", f"ps -T -p {pid} | grep -c UsbIsoAudio || true").stdout.strip()
    return int(c or "0")


def check_power_wakelock_held(serial: str) -> bool:
    txt = adb(serial, "shell", "dumpsys power | grep -i 'PaperPlay:UsbIsoAudio' || true").stdout
    return "PaperPlay:UsbIsoAudio" in txt


def run_single_soak(
    serial: str,
    label: str,
    fmt: str,
    source: str,
    screen_mode: str,
    duration_sec: int,
) -> Tuple[str, ParsedTelemetry, dict]:
    print(f"\n=== SOAK [{label}] fmt={fmt} source={source} mode={screen_mode} dur={duration_sec}s ===")
    ensure_safe_volume(serial)

    # Wake screen to configure playback cleanly
    adb(serial, "shell", "input", "keyevent", "224")
    adb(serial, "shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(1.5)

    dev_cmd(serial, "set_unified", ["--ez", "enabled", "true"])
    time.sleep(0.5)
    dev_cmd(serial, "set_bit_perfect", ["--ez", "enabled", "true"])
    time.sleep(1.0)

    if source == "local":
        fname = TRACK_24_96 if fmt == "96_24" else TRACK_16_44
        remote = f"{DEVICE_AUDIO_DIR}/{fname}"
        dev_cmd(serial, "play_files", ["--es", "paths", remote, "--ez", "repeat", "true"])
    else:
        # Telegram track: uses the active library queue track (repeat one)
        dev_cmd(serial, "clear_dev_test")
        dev_cmd(serial, "set_repeat", ["--es", "mode", "one"])
        dev_cmd(serial, "transport", ["--es", "action", "play"])
    time.sleep(3.0)

    st_start = read_status(serial)
    print(
        f"[START] rate={st_start.get('unifiedRate')} alt={st_start.get('unifiedAlt')} "
        f"slots={st_start.get('activeSlots')} wlHeld={st_start.get('usbWakeLockHeld')} "
        f"adpf={st_start.get('devUsbAdpf')} keepAlive={st_start.get('devUsbSilentKeepAlive')}"
    )

    if screen_mode == "screen_off":
        adb(serial, "shell", "svc", "power", "stayon", "false")
        adb(serial, "shell", "input", "keyevent", "223")  # KEYCODE_SLEEP
        time.sleep(2.0)
        wake = adb(serial, "shell", "dumpsys power | grep mWakefulness=").stdout.strip()
        print(f"[SCREEN_OFF] {wake}")
    else:
        adb(serial, "shell", "svc", "power", "stayon", "usb")

    t0 = time.monotonic()
    deadline = t0 + duration_sec
    while time.monotonic() < deadline:
        rem = deadline - time.monotonic()
        time.sleep(min(30.0, max(1.0, rem)))
        elapsed = int(time.monotonic() - t0)
        thr = get_usb_thread_count(serial)
        wl_sys = check_power_wakelock_held(serial)
        print(f"  [+{elapsed:4d}s/{duration_sec}s] UsbIsoAudio_threads={thr} sysWakeLock={wl_sys}")

    # Capture status & export report BEFORE pausing
    st_end = read_status(serial)
    out_file = os.path.join(SOAK_OUT_DIR, f"soak_{label}_{fmt}_{source}_{screen_mode}.txt")
    report_txt = export_full_report(serial, out_file)
    parsed = parse_telemetry_report(report_txt)

    # Supplement from live status counters
    parsed.underruns = max(parsed.underruns, int(st_end.get("underruns", "0") or "0"))
    parsed.pkt_errs = max(parsed.pkt_errs, int(st_end.get("pktErrors", "0") or "0"))
    parsed.pkt_shorts = max(parsed.pkt_shorts, int(st_end.get("pktShorts", "0") or "0"))
    parsed.reconfigures = max(parsed.reconfigures, int(st_end.get("realFaultReconfigures", "0") or "0"))

    dev_cmd(serial, "transport", ["--es", "action", "pause"])
    if screen_mode == "screen_off":
        adb(serial, "shell", "input", "keyevent", "224")  # KEYCODE_WAKEUP
    return f"{label}/{fmt}/{source}/{screen_mode}", parsed, st_end


def check_pause_timeout_wakelock(serial: str) -> bool:
    print("\n=== CHECK: PAUSE TIMEOUT (25s) WAKELOCK & THREAD RELEASE ===")
    ensure_safe_volume(serial)
    dev_cmd(serial, "play_files", ["--es", "paths", f"{DEVICE_AUDIO_DIR}/{TRACK_24_96}", "--ez", "repeat", "true"])
    time.sleep(3.0)
    st_play = read_status(serial)
    wl_playing = st_play.get("usbWakeLockHeld") == "true"
    thr_playing = get_usb_thread_count(serial)
    print(f"[PLAYING] usbWakeLockHeld={wl_playing} threads={thr_playing} sysWL={check_power_wakelock_held(serial)}")

    dev_cmd(serial, "transport", ["--es", "action", "pause"])
    time.sleep(28.0)
    st_after = read_status(serial)
    wl_after = st_after.get("usbWakeLockHeld") == "true"
    thr_after = get_usb_thread_count(serial)
    sys_wl_after = check_power_wakelock_held(serial)
    print(f"[AFTER 28s PAUSE] usbWakeLockHeld={wl_after} threads={thr_after} sysWL={sys_wl_after}")
    return wl_playing and thr_playing == 1 and (not wl_after) and thr_after == 0 and (not sys_wl_after)


def format_markdown_table(rows: List[Tuple[str, ParsedTelemetry]]) -> str:
    header = (
        "| Run | Underruns | PktErr / Short (Last) | MaxGap (us) | Late >15ms | MaxSlots/Wakeup | "
        "MinRingFill (B) | Slots | Escalations | Reconf | Fallback | Nice / Policy / FIFO / JNI / ADPF | CPUs | Verdict |"
    )
    sep = "|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---|"
    lines = [header, sep]
    for name, t in rows:
        min_fill = str(t.min_ring_fill_bytes) if t.min_ring_fill_bytes is not None else "n/a"
        sched = f"{t.nice}/{t.policy}/fifo={t.fifo_errno}/jni={t.jni_prio_rc}/adpf={t.adpf}"
        verdict = classify_verdict(t)
        lines.append(
            f"| {name} | {t.underruns} | {t.pkt_errs}/{t.pkt_shorts} ({t.last_pkt_err}) | "
            f"{t.max_cb_gap_us} | {t.total_late_cb_15ms} | {t.max_slots_wakeup} | "
            f"{min_fill} | {t.last_slots_in_flight} | {t.cushion_escalations} | {t.reconfigures} | "
            f"{t.fallbacks} | {sched} | 0x{t.cpu_mask_union:x} | **{verdict}** |"
        )
    return "\n".join(lines)


def main():
    p = argparse.ArgumentParser(description="Wireless-ADB 15-minute Screen-Off USB Soak Harness")
    p.add_argument("--serial", required=True, help="ADB device serial (e.g. 10.149.200.44:41225)")
    p.add_argument("--label", default="after", help="Run label (e.g. before, after, after_adpf, after_keepalive)")
    p.add_argument("--duration-sec", type=int, default=900, help="Duration per soak run in seconds (default 900 = 15 min)")
    p.add_argument("--formats", default="96_24,44_16", help="Comma-separated formats: 96_24,44_16")
    p.add_argument("--sources", default="local,telegram", help="Comma-separated sources: local,telegram")
    p.add_argument("--modes", default="screen_on,screen_off", help="Comma-separated modes: screen_on,screen_off")
    p.add_argument("--adpf", action="store_true", help="Enable ADPF experiment flag")
    p.add_argument("--keepalive", action="store_true", help="Enable silent AudioTrack keep-alive experiment flag")
    p.add_argument("--check-pause-wakelock", action="store_true", help="Verify wakelock release after 25s pause timeout")
    args = p.parse_args()

    formats = [x.strip() for x in args.formats.split(",") if x.strip()]
    sources = [x.strip() for x in args.sources.split(",") if x.strip()]
    modes = [x.strip() for x in args.modes.split(",") if x.strip()]

    rows: List[Tuple[str, ParsedTelemetry]] = []
    try:
        ensure_safe_volume(args.serial)
        dev_cmd(
            args.serial,
            "set_usb_experiments",
            [
                "--ez", "adpf", "true" if args.adpf else "false",
                "--ez", "keepalive", "true" if args.keepalive else "false",
            ],
        )
        time.sleep(0.4)

        for src in sources:
            for fmt in formats:
                for mode in modes:
                    name, parsed, _ = run_single_soak(args.serial, args.label, fmt, src, mode, args.duration_sec)
                    rows.append((name, parsed))

        pause_wl_ok = True
        if args.check_pause_wakelock:
            pause_wl_ok = check_pause_timeout_wakelock(args.serial)

        table = format_markdown_table(rows)
        print("\n=== SOAK SUMMARY TABLE ===")
        print(table)
        os.makedirs(SOAK_OUT_DIR, exist_ok=True)
        summary_path = os.path.join(SOAK_OUT_DIR, f"summary_{args.label}.md")
        with open(summary_path, "w", encoding="utf-8") as f:
            f.write(table + "\n")
            if args.check_pause_wakelock:
                f.write(f"\nPause-timeout wakelock release: {'PASS' if pause_wl_ok else 'FAIL'}\n")
        print(f"\nSaved summary -> {summary_path}")

        all_clean = all(classify_verdict(t) == "CLEAN_PASS" for _, t in rows) and pause_wl_ok
        sys.exit(0 if all_clean else 1)
    finally:
        dev_cmd(args.serial, "set_repeat", ["--es", "mode", "off"])
        dev_cmd(args.serial, "clear_dev_test")
        dev_cmd(args.serial, "transport", ["--es", "action", "pause"])
        adb(args.serial, "shell", "svc", "power", "stayon", "false")


if __name__ == "__main__":
    main()
