import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).parent))

from load_event_detector import (
    DetectorParams,
    EventCode,
    LoadState,
    MagSample,
    replay_csv,
    replay_samples,
)


PERIOD_MS = DetectorParams().sample_period_ms


def make_samples(segments, base=9000.0):
    samples = []
    seq = 0
    for duration_ms, dev_rel in segments:
        count = max(1, duration_ms // PERIOD_MS)
        mz = base * (1.0 + dev_rel)
        for _ in range(count):
            samples.append(MagSample(seq=seq, t_ms=seq * PERIOD_MS, mx=0.0, my=0.0, mz=mz))
            seq += 1
    return samples


class LoadEventDetectorTests(unittest.TestCase):
    def event_codes(self, segments, params=None):
        _, events = replay_samples(make_samples(segments), params)
        return [event.event_code for event in events]

    def test_quiet_idle_adapts_without_false_events(self):
        samples = make_samples([(2000, 0.0), (2000, 0.004), (2000, -0.003)])
        rows, events = replay_samples(samples)

        self.assertEqual(events, [])
        self.assertEqual(rows[-1].load_state, LoadState.IDLE)
        self.assertLess(abs(rows[-1].dev_rel), 0.01)

    def test_one_clean_step(self):
        codes = self.event_codes([(500, 0.0), (700, 0.08), (500, 0.0)])
        self.assertEqual(codes, [EventCode.STEP])

    def test_sustained_load_over_two_seconds(self):
        codes = self.event_codes([(500, 0.0), (2400, 0.08), (500, 0.0)])
        self.assertEqual(codes, [EventCode.SUSTAINED])

    def test_short_weak_bump_is_ignored(self):
        codes = self.event_codes([(500, 0.0), (180, 0.035), (500, 0.0)])
        self.assertEqual(codes, [EventCode.IGNORED])

    def test_release_bounce_does_not_split_one_event(self):
        codes = self.event_codes(
            [
                (500, 0.0),
                (500, 0.08),
                (40, 0.0),
                (500, 0.07),
                (500, 0.0),
            ]
        )
        self.assertEqual(codes, [EventCode.STEP])

    def test_post_event_refractory_prevents_double_counting(self):
        codes = self.event_codes(
            [
                (500, 0.0),
                (500, 0.08),
                (120, 0.0),
                (40, 0.08),
                (500, 0.0),
            ]
        )
        self.assertEqual(codes, [EventCode.STEP])

    def test_slow_unloaded_drift_is_tracked(self):
        samples = []
        base = 9000.0
        for seq in range(500):
            drift = 0.006 * seq / 499
            samples.append(
                MagSample(seq=seq, t_ms=seq * PERIOD_MS, mx=0.0, my=0.0, mz=base * (1.0 + drift))
            )

        rows, events = replay_samples(samples)

        self.assertEqual(events, [])
        self.assertLess(abs(rows[-1].dev_rel), 0.01)

    def test_saved_recording_regressions(self):
        expected = {
            "data3_3stepsthenfall.csv": {EventCode.STEP: 3},
            "data4obstacles.csv": {EventCode.STEP: 7},
            "data5.csv": {EventCode.STEP: 3},
            "data6.csv": {EventCode.SUSTAINED: 1, EventCode.IGNORED: 1},
        }

        data_dir = Path(__file__).parent
        for name, counts in expected.items():
            with self.subTest(name=name):
                _, events = replay_csv(data_dir / name)
                actual = {}
                for event in events:
                    actual[event.event_code] = actual.get(event.event_code, 0) + 1
                self.assertEqual(actual, counts)


if __name__ == "__main__":
    unittest.main()
