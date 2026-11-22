#!/usr/bin/env python3
"""
Automated wireless-ADB verification suite for PaperPlay Unified Bit-Perfect USB Audio.

Safety policy:
- Never raises STREAM_MUSIC above 40/160 (<= 4/16); lowers if above 40 before playing.
- Always pauses playback and restores `svc power stayon false` on exit.

Examples:
  python3 scripts/run_device_bitperfect_verification.py --serial 10.149.200.44:41225 --pcm-dump
  python3 scripts/run_device_bitperfect_verification.py --serial 10.149.200.44:41225 --all
"""

import argparse
import os
import re
import subprocess
import sys
import time

PKG = "io.max.paperplay.compose"
ACTION = "io.max.paperplay.DEV_USB_CMD"
TEST_DIR = os.path.join("scripts", "test_audio")
CAPTURE_DIR = os.path.join("scratch", "captures")
DEVICE_AUDIO_DIR = f"/sdcard/Android/data/{PKG}/files/test_audio"

TRACK_16_44 = "test_sine_ramp_16bit_44k.flac"
TRACK_24_96 = "test_sine_ramp_24bit_96k.flac"
TRACK_24_48_LSB = "test_lsb_pattern_24bit_48k.flac"


def adb(serial, *args, check=True, text=True):
    cmd = ["adb", "-s", serial, *args]
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=text, check=check)


def dev_cmd(serial, cmd, extras=None):
    args = ["shell", "am", "broadcast", "-a", ACTION, "-n", f"{PKG}/io.max.paperplay.player.usb.UsbDevTestReceiver", "--es", "cmd", cmd]
    if extras:
        args.extend(extras)
    return adb(serial, *args, check=False)


def read_status(serial):
    dev_cmd(serial, "status")
    time.sleep(0.3)
    res = adb(serial, "shell", f"run-as {PKG} cat cache/usb_dev_status.txt", check=False)
    out = {}
    for line in res.stdout.splitlines():
        if "=" in line and not line.startswith("---"):
            k, v = line.split("=", 1)
            out[k.strip()] = v.strip()
    return out, res.stdout


def get_stream_music_levels(serial):
    res = adb(serial, "shell", "dumpsys audio | grep -A 8 '^- STREAM_MUSIC:'", check=False)
    txt = res.stdout
    max_m = re.search(r"Max:\s*(\d+)", txt)
    max_v = int(max_m.group(1)) if max_m else 160
    levels = {}
    for m in re.finditer(r"\((\w+)\):\s*(\d+)", txt):
        levels[m.group(1)] = int(m.group(2))
    return max_v, levels, txt


def ensure_safe_volume(serial, max_safe=40):
    max_v, levels, _ = get_stream_music_levels(serial)
    usb_idx = levels.get("usb_headset", levels.get("usb_device", 0))
    stalled = 0
    while usb_idx > max_safe:
        print(f"[SAFETY] Lowering usb_headset volume {usb_idx}/{max_v} -> <= {max_safe}/{max_v}")
        adb(serial, "shell", "input", "keyevent", "25", check=False)
        time.sleep(0.25)
        _, levels, _ = get_stream_music_levels(serial)
        new_idx = levels.get("usb_headset", levels.get("usb_device", 0))
        if new_idx < usb_idx:
            stalled = 0
        else:
            stalled += 1
            if stalled >= 3:
                break
        usb_idx = new_idx
    if usb_idx > max_safe:
        raise RuntimeError(f"Refusing to run with usb_headset volume {usb_idx}/{max_v} > {max_safe}")
    print(f"[SAFETY] STREAM_MUSIC usb_headset={usb_idx}/{max_v} (safe <= {max_safe})")
    return usb_idx, max_v


def check_dac_connected(serial):
    res = adb(serial, "shell", "dumpsys usb | grep -E 'host_connected=|kernel_state=|product=' | head -n 10", check=False)
    return "host_connected=true" in res.stdout or "CONFIGURED" in res.stdout or "Audiocular" in res.stdout


def get_media_state(serial):
    res = adb(serial, "shell", f"dumpsys media_session | grep -A 8 '{PKG}' | grep 'state=PlaybackState' | head -n 1", check=False)
    line = res.stdout.strip()
    state_m = re.search(r"state=([A-Z_]+)\((\d+)\)", line)
    pos_m = re.search(r"position=(-?\d+)", line)
    spd_m = re.search(r"speed=([0-9.]+)", line)
    err_m = re.search(r"error=([^\s,}]+(?:\s+[^,}]+)?)", line)
    return {
        "raw": line,
        "state": state_m.group(1) if state_m else "UNKNOWN",
        "pos_ms": int(pos_m.group(1)) if pos_m else -1,
        "speed": float(spd_m.group(1)) if spd_m else 0.0,
        "error": err_m.group(1) if err_m else None,
    }


def get_usb_thread_count(serial):
    pid = adb(serial, "shell", f"pidof {PKG}", check=False).stdout.strip()
    if not pid:
        return None, 0
    ps = adb(serial, "shell", f"ps -T -p {pid} | grep -c UsbIsoAudio || true", check=False).stdout.strip()
    return pid, int(ps or "0")


def ensure_app_foreground(serial):
    adb(serial, "shell", "svc", "power", "stayon", "usb", check=False)
    adb(serial, "shell", "input", "keyevent", "224", check=False)
    adb(serial, "shell", "am", "start", "-n", f"{PKG}/.MainActivity", check=False)
    time.sleep(2.0)


def push_test_assets(serial):
    adb(serial, "shell", f"mkdir -p {DEVICE_AUDIO_DIR}", check=True)
    for fname in (TRACK_16_44, TRACK_24_96, TRACK_24_48_LSB):
        local = os.path.join(TEST_DIR, fname)
        if not os.path.exists(local):
            subprocess.run([sys.executable, os.path.join("scripts", "verify_bitperfect.py"), "--generate-test-signals"], check=True)
        remote = f"{DEVICE_AUDIO_DIR}/{fname}"
        adb(serial, "push", local, remote, check=True)
    print(f"[ASSETS] Pushed 3 FLAC test signals to {DEVICE_AUDIO_DIR}")


def run_pcm_dump_checks(serial):
    print("\n=== TEST: PCM DUMP SAMPLE-EXACTNESS (UNIFIED PATH) ===")
    os.makedirs(CAPTURE_DIR, exist_ok=True)
    dev_cmd(serial, "set_unified", ["--ez", "enabled", "true"])
    time.sleep(0.6)
    dev_cmd(serial, "set_bit_perfect", ["--ez", "enabled", "true"])
    time.sleep(1.0)
    dev_cmd(serial, "set_speed", ["--ef", "speed", "1.0"])
    time.sleep(0.4)

    cases = [
        (TRACK_16_44, "16-bit/44.1k"),
        (TRACK_24_96, "24-bit/96k"),
        (TRACK_24_48_LSB, "24-bit/48k LSB pattern"),
    ]
    all_ok = True
    for fname, label in cases:
        remote = f"{DEVICE_AUDIO_DIR}/{fname}"
        print(f"\n--- Capturing {label} ({fname}) ---")
        dev_cmd(serial, "transport", ["--es", "action", "pause"])
        time.sleep(0.5)
        dev_cmd(serial, "arm_pcm_dump", ["--ei", "duration_sec", "6"])
        time.sleep(0.3)
        dev_cmd(serial, "play_files", ["--es", "paths", remote])
        time.sleep(8.5)
        st, _ = read_status(serial)
        dump_path = st.get("pcmDumpPath", "")
        print(f"Status: rate={st.get('unifiedRate')} bitDepth={st.get('unifiedBitDepth')} alt={st.get('unifiedAlt')} subslot={st.get('unifiedSubslotBytes')} dump='{st.get('pcmDumpStatus')}' path={dump_path}")
        if not dump_path:
            print(f"[FAIL] No dump path produced for {label}")
            all_ok = False
            continue
        rel_cache = dump_path.split("/cache/", 1)[-1]
        local_cap = os.path.join(CAPTURE_DIR, f"unified_{os.path.basename(dump_path)}")
        raw_res = adb(serial, "exec-out", f"run-as {PKG} cat cache/{rel_cache}", check=True, text=False)
        with open(local_cap, "wb") as f:
            f.write(raw_res.stdout)
        print(f"Pulled {len(raw_res.stdout)} bytes -> {local_cap}")
        cmp_proc = subprocess.run(
            [sys.executable, os.path.join("scripts", "verify_bitperfect.py"), "--source", os.path.join(TEST_DIR, fname), "--capture", local_cap],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True
        )
        print(cmp_proc.stdout)
        if cmp_proc.returncode != 0:
            all_ok = False
    dev_cmd(serial, "transport", ["--es", "action", "pause"])
    return all_ok


def run_mixed_rate_checks(serial):
    print("\n=== TEST: MIXED RATE SEQUENCE 44.1/16 -> 96/24 -> 48/24 x3 (IN-PLACE RECONFIGURE) ===")
    dev_cmd(serial, "set_unified", ["--ez", "enabled", "true"])
    time.sleep(0.5)
    dev_cmd(serial, "set_bit_perfect", ["--ez", "enabled", "true"])
    time.sleep(1.0)
    seq = [TRACK_16_44, TRACK_24_96, TRACK_24_48_LSB] * 3
    paths = ",".join(f"{DEVICE_AUDIO_DIR}/{f}" for f in seq)
    adb(serial, "logcat", "-c", check=False)
    dev_cmd(serial, "play_files", ["--es", "paths", paths])
    expected_rates = [44100, 96000, 48000] * 3
    ok = True
    in_place_count = 0
    for idx, exp_rate in enumerate(expected_rates):
        time.sleep(2.5)
        st, _ = read_status(serial)
        ms = get_media_state(serial)
        pid, thr = get_usb_thread_count(serial)
        got_rate = int(st.get("unifiedRate", "0"))
        got_alt = int(st.get("unifiedAlt", "-1"))
        got_depth = int(st.get("unifiedBitDepth", "0"))
        in_place = st.get("unifiedInPlace", "false") == "true"
        underruns = int(st.get("underruns", "-1"))
        urb_err = int(st.get("urbErrors", "-1"))
        if idx > 0 and in_place:
            in_place_count += 1
        print(f"Step {idx+1}/9 ({seq[idx]}): exp={exp_rate} gotRate={got_rate} depth={got_depth} alt={got_alt} inPlace={in_place} underruns={underruns} urbErr={urb_err} thr={thr} state={ms['state']}")
        if got_rate != exp_rate or underruns != 0 or urb_err != 0 or thr != 1:
            ok = False
        if idx < len(expected_rates) - 1:
            dev_cmd(serial, "transport", ["--es", "action", "next"])
    logs = adb(serial, "logcat", "-d", check=False).stdout
    bad = [ln for ln in logs.splitlines() if re.search(r"Rate mismatch|Alt mismatch|Fallback|underrun|SIGABRT", ln, re.I)]
    print(f"In-place transitions after #1: {in_place_count}/8, error/mismatch log lines: {len(bad)}")
    for b in bad[:10]:
        print(f"  LOG: {b}")
    dev_cmd(serial, "transport", ["--es", "action", "pause"])
    return ok and in_place_count == 8 and len(bad) == 0


def run_transport_checks(serial):
    print("\n=== TEST: TRANSPORT CONTROLS (NEXT / PREV / SEEK / PAUSE-NEXT-RESUME) ===")
    paths = ",".join(f"{DEVICE_AUDIO_DIR}/{f}" for f in (TRACK_16_44, TRACK_24_96, TRACK_24_48_LSB))
    dev_cmd(serial, "set_unified", ["--ez", "enabled", "true"])
    dev_cmd(serial, "set_bit_perfect", ["--ez", "enabled", "true"])
    time.sleep(0.8)
    dev_cmd(serial, "play_files", ["--es", "paths", paths])
    time.sleep(2.2)

    def check_step(name, wait_s=1.8):
        time.sleep(wait_s)
        st, _ = read_status(serial)
        ms = get_media_state(serial)
        _, thr = get_usb_thread_count(serial)
        print(f"[{name}] state={ms['state']} pos={ms['pos_ms']} rate={st.get('unifiedRate')} alt={st.get('unifiedAlt')} frames={st.get('framesConsumed')} underruns={st.get('underruns')} thr={thr}")
        return st, ms, thr

    _, m0, t0 = check_step("initial 44.1k", 0.1)
    dev_cmd(serial, "transport", ["--es", "action", "next"])
    s1, m1, t1 = check_step("next -> 96k")
    dev_cmd(serial, "transport", ["--es", "action", "prev"])
    s2, m2, t2 = check_step("prev -> 44.1k")
    dev_cmd(serial, "transport", ["--es", "action", "seek", "--el", "pos_ms", "10000"])
    s3, m3, t3 = check_step("seek -> 10000ms")
    dev_cmd(serial, "transport", ["--es", "action", "pause"])
    time.sleep(0.8)
    dev_cmd(serial, "transport", ["--es", "action", "next"])
    time.sleep(0.6)
    dev_cmd(serial, "transport", ["--es", "action", "play"])
    s4, m4, t4 = check_step("pause-next-resume -> 96k", 2.0)
    dev_cmd(serial, "transport", ["--es", "action", "pause"])
    return (
        s1.get("unifiedRate") == "96000" and
        s2.get("unifiedRate") == "44100" and
        m3["pos_ms"] >= 9500 and
        s4.get("unifiedRate") == "96000" and m4["state"] == "PLAYING" and t4 == 1
    )


def run_speed_checks(serial):
    print("\n=== TEST: SPEED BYPASS 1.0x -> 1.5x -> 1.0x ===")
    dev_cmd(serial, "set_unified", ["--ez", "enabled", "true"])
    dev_cmd(serial, "set_bit_perfect", ["--ez", "enabled", "true"])
    time.sleep(0.8)
    dev_cmd(serial, "play_files", ["--es", "paths", f"{DEVICE_AUDIO_DIR}/{TRACK_24_96}"])
    time.sleep(2.2)
    s0, _ = read_status(serial)
    _, t0 = get_usb_thread_count(serial)
    print(f"[1.0x initial] modeActive={s0.get('unifiedModeActive')} status={s0.get('bitPerfectStatus')} thr={t0}")

    dev_cmd(serial, "set_speed", ["--ef", "speed", "1.5"])
    time.sleep(2.5)
    s1, _ = read_status(serial)
    m1 = get_media_state(serial)
    _, t1 = get_usb_thread_count(serial)
    print(f"[1.5x bypass] modeActive={s1.get('unifiedModeActive')} status={s1.get('bitPerfectStatus')} coreSpeed={s1.get('speed')} speed={m1['speed']} state={m1['state']} thr={t1}")

    dev_cmd(serial, "set_speed", ["--ef", "speed", "1.0"])
    time.sleep(2.8)
    s2, _ = read_status(serial)
    m2 = get_media_state(serial)
    _, t2 = get_usb_thread_count(serial)
    print(f"[1.0x return] modeActive={s2.get('unifiedModeActive')} status={s2.get('bitPerfectStatus')} coreSpeed={s2.get('speed')} speed={m2['speed']} state={m2['state']} rate={s2.get('unifiedRate')} thr={t2}")
    dev_cmd(serial, "transport", ["--es", "action", "pause"])
    return (
        t0 == 1
        and t1 == 0
        and "Bypassed" in s1.get("bitPerfectStatus", "")
        and t2 == 1
        and s2.get("unifiedModeActive") == "true"
        and "Active" in s2.get("bitPerfectStatus", "")
    )


def tap_mini_player_play_pause(serial):
    dump = adb(serial, "shell", "uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1 && cat /data/local/tmp/ui.xml", check=False).stdout
    m = re.search(r'content-desc="(?:Play|Pause)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump)
    if m:
        cx = (int(m.group(1)) + int(m.group(3))) // 2
        cy = (int(m.group(2)) + int(m.group(4))) // 2
        adb(serial, "shell", "input", "tap", str(cx), str(cy), check=False)
        return True
    return False


def wait_for_active_player(serial, timeout_s=35.0):
    t0 = time.monotonic()
    deadline = t0 + timeout_s
    last_tap = 0.0
    while time.monotonic() < deadline:
        st, _ = read_status(serial)
        if st.get("activePlayerReady") == "true":
            return True
        now = time.monotonic()
        # Wake LazyPlayerEngine once MainActivity mini-player has rendered
        if now - t0 >= 2.5 and now - last_tap >= 5.0:
            if tap_mini_player_play_pause(serial):
                last_tap = now
        time.sleep(0.8)
    raise RuntimeError(f"Timed out waiting {timeout_s}s for PlaybackCore.activePlayer")


def run_runtime_switch_check(serial):
    print("\n=== TEST: RUNTIME SWITCH UNIFIED <-> LEGACY (NO REBUILD) ===")
    dev_cmd(serial, "set_bit_perfect", ["--ez", "enabled", "true"])
    time.sleep(1.0)
    dev_cmd(serial, "play_files", ["--es", "paths", f"{DEVICE_AUDIO_DIR}/{TRACK_16_44}"])
    time.sleep(2.5)
    dev_cmd(serial, "set_unified", ["--ez", "enabled", "false"])
    time.sleep(4.0)
    dev_cmd(serial, "transport", ["--es", "action", "play"])
    time.sleep(1.2)
    s_leg, _ = read_status(serial)
    m_leg = get_media_state(serial)
    print(f"[Legacy] useUnified={s_leg.get('useUnifiedBitPerfect')} legacyMode={s_leg.get('legacyMode')} state={m_leg['state']} error={m_leg['error']}")

    dev_cmd(serial, "set_unified", ["--ez", "enabled", "true"])
    time.sleep(4.0)
    dev_cmd(serial, "transport", ["--es", "action", "play"])
    time.sleep(1.5)
    s_uni, _ = read_status(serial)
    m_uni = get_media_state(serial)
    print(f"[Unified] useUnified={s_uni.get('useUnifiedBitPerfect')} unifiedActive={s_uni.get('unifiedModeActive')} state={m_uni['state']} error={m_uni['error']}")
    dev_cmd(serial, "transport", ["--es", "action", "pause"])
    return s_leg.get("useUnifiedBitPerfect") == "false" and s_uni.get("useUnifiedBitPerfect") == "true" and m_leg["state"] == "PLAYING" and m_uni["state"] == "PLAYING"


def read_media_item_id(raw_line):
    m = re.search(r"active item id=(\d+)", raw_line)
    return int(m.group(1)) if m else -1


def run_off_60s_check(serial):
    print("\n=== TEST: 60s OFF-MODE RUN WITH DAC PLUGGED IN ===")
    dev_cmd(serial, "set_unified", ["--ez", "enabled", "true"])
    dev_cmd(serial, "set_bit_perfect", ["--ez", "enabled", "false"])
    time.sleep(1.0)
    # Restart in OFF mode so /proc/<pid>/maps verifies a fresh OFF session (0 libusb mappings)
    dev_cmd(serial, "transport", ["--es", "action", "pause"])
    time.sleep(0.3)
    adb(serial, "shell", "am", "force-stop", PKG, check=False)
    time.sleep(0.8)
    ensure_app_foreground(serial)
    ensure_safe_volume(serial)
    wait_for_active_player(serial)
    dev_cmd(serial, "set_unified", ["--ez", "enabled", "true"])
    dev_cmd(serial, "set_bit_perfect", ["--ez", "enabled", "false"])
    time.sleep(1.0)
    paths = ",".join(f"{DEVICE_AUDIO_DIR}/{f}" for f in (TRACK_24_96, TRACK_16_44, TRACK_24_48_LSB))
    dev_cmd(serial, "play_files", ["--es", "paths", paths])
    time.sleep(2.5)
    t_prev = time.monotonic()
    m_prev = get_media_state(serial)
    prev_item = read_media_item_id(m_prev["raw"])
    buffering_stalls = 0
    within_track_pos_ms = 0
    within_track_wall_ms = 0.0
    t_run_start = t_prev
    for step in range(20):
        time.sleep(3.0)
        t_now = time.monotonic()
        ms = get_media_state(serial)
        curr_item = read_media_item_id(ms["raw"])
        if ms["state"] == "BUFFERING":
            buffering_stalls += 1
        dt_ms = (t_now - t_prev) * 1000.0
        if curr_item == prev_item and ms["pos_ms"] >= m_prev["pos_ms"]:
            dpos = ms["pos_ms"] - m_prev["pos_ms"]
            within_track_pos_ms += dpos
            within_track_wall_ms += dt_ms
        t_prev = t_now
        m_prev = ms
        prev_item = curr_item
    total_wall_s = time.monotonic() - t_run_start
    pid, thr = get_usb_thread_count(serial)
    maps = adb(serial, "shell", f"run-as {PKG} grep -cE 'libusb_audio|libusb-1\\.0' /proc/{pid}/maps || true", check=False).stdout.strip()
    dev_cmd(serial, "transport", ["--es", "action", "pause"])
    pace = within_track_pos_ms / within_track_wall_ms if within_track_wall_ms > 0 else 0.0
    print(f"60s OFF-mode ({total_wall_s:.1f}s): within_track_pos={within_track_pos_ms}ms over within_track_wall={within_track_wall_ms:.0f}ms, pace={pace*1000:.1f} ms/s, BUFFERING_stalls={buffering_stalls}, usb_threads={thr}, libusb_maps={maps}")
    return total_wall_s >= 58.0 and buffering_stalls == 0 and thr == 0 and maps == "0" and 0.90 <= pace <= 1.10


def read_dac_baseline(serial):
    dev_cmd(serial, "read_dac_baseline")
    for _ in range(10):
        time.sleep(0.5)
        res = adb(serial, "shell", f"run-as {PKG} cat cache/usb_dac_baseline.txt 2>/dev/null || true", check=False)
        if "DAC_BASELINE " in res.stdout:
            return res.stdout.strip()
    return ""


def run_volume_audit(serial):
    print("\n=== TEST: VOLUME CURVE 0..MAX SWEEP, GET_CUR READBACK, RESTORE-BEFORE-RELEASE & LEVEL MATCH ===")
    dev_cmd(serial, "transport", ["--es", "action", "pause"])
    dev_cmd(serial, "set_bit_perfect", ["--ez", "enabled", "false"])
    time.sleep(1.5)
    safe_idx, max_v = ensure_safe_volume(serial)
    before_baseline = read_dac_baseline(serial)
    print(f"Pre-toggle DAC baseline at index {safe_idx}/{max_v}: {before_baseline}")

    dev_cmd(serial, "volume_audit")
    audit_txt = ""
    for _ in range(30):
        time.sleep(1.0)
        res = adb(serial, "shell", f"run-as {PKG} cat cache/usb_volume_audit.txt 2>/dev/null || true", check=False)
        if "SUMMARY " in res.stdout:
            audit_txt = res.stdout
            break
    if not audit_txt:
        print("[FAIL] volume_audit timed out")
        return False
    print(audit_txt)

    # Toggle Bit-perfect ON -> inspect hardware volume readback at same safe system index -> toggle OFF -> verify baseline level
    dev_cmd(serial, "set_unified", ["--ez", "enabled", "true"])
    time.sleep(0.4)
    dev_cmd(serial, "set_bit_perfect", ["--ez", "enabled", "true"])
    dev_cmd(serial, "play_files", ["--es", "paths", f"{DEVICE_AUDIO_DIR}/{TRACK_16_44}"])
    time.sleep(2.0)
    st_on, _ = read_status(serial)
    on_raw = st_on.get("unifiedVolReadbackRaw", "0")
    on_db = int(on_raw) / 256.0 if on_raw.lstrip("-").isdigit() else 0.0
    dev_cmd(serial, "transport", ["--es", "action", "pause"])
    time.sleep(0.3)
    dev_cmd(serial, "set_bit_perfect", ["--ez", "enabled", "false"])
    time.sleep(1.5)
    after_baseline = read_dac_baseline(serial)
    ensure_safe_volume(serial)
    baseline_equal = bool(before_baseline) and before_baseline == after_baseline
    print(
        f"Level comparison at same system index {safe_idx}/{max_v}: "
        f"BEFORE {before_baseline} | ON readback={on_raw} ({on_db:.2f} dB) | "
        f"AFTER OFF {after_baseline} | baselineEqual={baseline_equal}"
    )
    return (
        "monotonic=true" in audit_txt
        and "cliffDetected=false" in audit_txt
        and "muteAt0Ok=true" in audit_txt
        and "unmutedAt1Ok=true" in audit_txt
        and "restoredMatchesPreSession=true" in audit_txt
        and baseline_equal
    )


def main():
    p = argparse.ArgumentParser(description="Wireless adb verification for PaperPlay Unified Bit-Perfect")
    p.add_argument("--serial", required=True, help="ADB serial (e.g. 10.149.200.44:41225)")
    p.add_argument("--pcm-dump", action="store_true")
    p.add_argument("--mixed-rate", action="store_true")
    p.add_argument("--transport", action="store_true")
    p.add_argument("--speed", action="store_true")
    p.add_argument("--runtime-switch", action="store_true")
    p.add_argument("--off-60s", action="store_true")
    p.add_argument("--volume-audit", action="store_true")
    p.add_argument("--all", action="store_true")
    args = p.parse_args()

    s = args.serial
    try:
        ensure_app_foreground(s)
        ensure_safe_volume(s)
        wait_for_active_player(s)
        if not check_dac_connected(s):
            print("NO_USB_DAC_ATTACHED")
            sys.exit(2)
        push_test_assets(s)
        results = {}
        # Run 60s OFF check first so `/proc/<pid>/maps` verifies a clean OFF process before ON-mode lazy load
        if args.all or args.off_60s:
            results["off_60s"] = run_off_60s_check(s)
        if args.all or args.pcm_dump:
            results["pcm_dump"] = run_pcm_dump_checks(s)
        if args.all or args.mixed_rate:
            results["mixed_rate"] = run_mixed_rate_checks(s)
        if args.all or args.transport:
            results["transport"] = run_transport_checks(s)
        if args.all or args.speed:
            results["speed"] = run_speed_checks(s)
        if args.all or args.runtime_switch:
            results["runtime_switch"] = run_runtime_switch_check(s)
        if args.all or args.volume_audit:
            results["volume_audit"] = run_volume_audit(s)

        print("\n=== SUITE SUMMARY ===")
        for k, v in results.items():
            print(f"  {k}: {'PASS' if v else 'FAIL'}")
        sys.exit(0 if all(results.values()) else 1)
    finally:
        dev_cmd(s, "clear_dev_test")
        dev_cmd(s, "transport", ["--es", "action", "pause"])
        adb(s, "shell", "svc", "power", "stayon", "false", check=False)


if __name__ == "__main__":
    main()
