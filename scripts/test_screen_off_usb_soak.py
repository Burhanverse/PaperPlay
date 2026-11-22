#!/usr/bin/env python3
"""Unit tests for scripts/run_screen_off_usb_soak.py log parser and decision rule."""

import unittest
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
import run_screen_off_usb_soak as soak


class SoakParserAndDecisionRuleTest(unittest.TestCase):
    def test_clean_pass_parsing(self):
        sample = """
[native] @1000ms THREAD_SCHED_DETAIL: tid=4321 setprio_rc=0 setprio_errno=0 fifo_rc=-1 fifo_errno=1 jni_prio_rc=1 adpf=false nice=-19 policy=0 cpu=6 cgroup=0::/top-app
[native] @6000ms PERIODIC_STATS: underruns=0 ring_fill_bytes=524288 pkt_errs=0 pkt_shorts=0 last_pkt_err=0
[native] @6000ms JITTER_STATS: max_cb_gap_us=4250 late_cb_15ms=0 max_slots_wakeup=2 min_ring_fill=480000 slots_in_flight=16 cpu_mask=0xc0 last_cpu=6
"""
        t = soak.parse_telemetry_report(sample)
        self.assertEqual(t.samples_jitter, 1)
        self.assertEqual(t.max_cb_gap_us, 4250)
        self.assertEqual(t.total_late_cb_15ms, 0)
        self.assertEqual(t.max_slots_wakeup, 2)
        self.assertEqual(t.min_ring_fill_bytes, 480000)
        self.assertEqual(t.last_slots_in_flight, 16)
        self.assertEqual(t.cpu_mask_union, 0xC0)
        self.assertEqual(t.nice, "-19")
        self.assertEqual(t.fifo_errno, "1")
        self.assertEqual(t.jni_prio_rc, "1")
        self.assertEqual(t.cgroup, "0::/top-app")
        self.assertEqual(soak.classify_verdict(t), "CLEAN_PASS")

    def test_bus_vbus_verdict(self):
        sample = """
[native] @6000ms URB_PACKET_ERR: status=1 top_status=0 slot=3 pkt_idx=12 pkt_errs=4 pkt_shorts=0
[native] @6000ms PERIODIC_STATS: underruns=0 ring_fill_bytes=400000 pkt_errs=4 pkt_shorts=0 last_pkt_err=1
[native] @6000ms JITTER_STATS: max_cb_gap_us=4500 late_cb_15ms=0 max_slots_wakeup=2 min_ring_fill=380000 slots_in_flight=16 cpu_mask=0x30 last_cpu=4
"""
        t = soak.parse_telemetry_report(sample)
        self.assertEqual(t.pkt_errs, 4)
        self.assertEqual(t.last_pkt_err, 1)
        self.assertEqual(soak.classify_verdict(t), "BUS/VBUS")

    def test_cpu_scheduling_verdict(self):
        sample = """
[native] @6000ms CUSHION_ESCALATE: reason=late_cb old_slots=16 new_slots=32 trigger_val=28500
[native] @6000ms PERIODIC_STATS: underruns=0 ring_fill_bytes=600000 pkt_errs=0 pkt_shorts=0 last_pkt_err=0
[native] @6000ms JITTER_STATS: max_cb_gap_us=28500 late_cb_15ms=3 max_slots_wakeup=7 min_ring_fill=510000 slots_in_flight=32 cpu_mask=0x0f last_cpu=1
"""
        t = soak.parse_telemetry_report(sample)
        self.assertEqual(t.cushion_escalations, 1)
        self.assertEqual(soak.classify_verdict(t), "CPU_SCHEDULING")

    def test_producer_network_verdict(self):
        sample = """
[native] @6000ms PERIODIC_STATS: underruns=5 ring_fill_bytes=0 pkt_errs=0 pkt_shorts=0 last_pkt_err=0
[native] @6000ms JITTER_STATS: max_cb_gap_us=4100 late_cb_15ms=0 max_slots_wakeup=1 min_ring_fill=0 slots_in_flight=32 cpu_mask=0x40 last_cpu=6
"""
        t = soak.parse_telemetry_report(sample)
        self.assertEqual(soak.classify_verdict(t), "PRODUCER/NETWORK")


if __name__ == "__main__":
    unittest.main()
