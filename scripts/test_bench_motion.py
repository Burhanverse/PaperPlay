import json
import tempfile
import unittest
from pathlib import Path

from bench_motion import compare_reports, parse_framestats_csv, parse_refresh_rate, summarize_frames


SAMPLE_FRAMESTATS = """
---PROFILEDATA---
Flags,IntendedVsync,Vsync,OldestInputEvent,NewestInputEvent,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted,DequeueBufferDuration,QueueBufferDuration,GpuCompleted
0,1000000000,1000000000,0,0,1000100000,1000200000,1000300000,1001000000,1002000000,1002100000,1003000000,1005000000,1008000000,0,0,0
0,2000000000,2000000000,0,0,2000100000,2000200000,2000300000,2001000000,2002000000,2002100000,2003000000,2010000000,2022000000,0,0,0
0,3000000000,3000000000,0,0,3000100000,3000200000,3000300000,3001000000,3002000000,3002100000,3003000000,3006000000,3010000000,0,0,0
0,4000000000,4000000000,0,0,4000100000,4000200000,4000300000,4001000000,4002000000,4002100000,4003000000,4007000000,4011000000,0,0,0
---PROFILEDATA---
"""


class BenchMotionTest(unittest.TestCase):
    def test_parse_framestats_and_summarize(self):
        frames = parse_framestats_csv(SAMPLE_FRAMESTATS)
        self.assertEqual([8.0, 22.0, 10.0, 11.0], frames)
        s = summarize_frames(frames, budget_ms=16.67)
        self.assertEqual(4, s["total_frames"])
        self.assertEqual(1, s["jank_frames"])
        self.assertEqual(1, s["over_20ms"])
        self.assertEqual(22.0, s["max_ms"])
        self.assertEqual(1, s["worst_index"])
        self.assertEqual("middle", s["worst_phase"])

    def test_parse_refresh_rate(self):
        self.assertEqual(120.0, parse_refresh_rate("renderFrameRate 120.00"))
        self.assertEqual(90.0, parse_refresh_rate("mode: 1080x2400 90.0 fps"))
        self.assertEqual(60.0, parse_refresh_rate("no rate here"))

    def test_compare_rejects_mismatched_frame_counts(self):
        with tempfile.TemporaryDirectory() as td:
            p1 = Path(td) / "base.json"
            p2 = Path(td) / "after.json"
            p1.write_text(json.dumps({"flows": [{"flow": "a", "total_frames": 100, "p95_ms": 12.0, "max_ms": 18.0, "jank_pct": 1.0}]}))
            p2.write_text(json.dumps({"flows": [{"flow": "a", "total_frames": 250, "p95_ms": 10.0, "max_ms": 14.0, "jank_pct": 0.0}]}))
            self.assertEqual(1, compare_reports(p1, p2))


if __name__ == "__main__":
    unittest.main()
