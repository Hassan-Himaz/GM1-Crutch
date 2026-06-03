import subprocess
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
CPP_DIR = REPO_ROOT / "cpp"
DETECTOR_SRC = CPP_DIR / "GM1Firmware" / "src" / "LoadStepDetector.cpp"
REPLAY_SRC = CPP_DIR / "replay_load_step_detector.cpp"
INCLUDE_DIR = CPP_DIR / "GM1Firmware" / "src"


EXPECTED = {
    "data10_hassan.csv": {
        "rows": 881,
        "events": 7,
        "max_force_kg": 18.226267741081486,
        "startup_baseline_uT": 768.6514053707559,
    },
    "data11.csv": {
        "rows": 3022,
        "events": 5,
        "max_force_kg": 15.481780716980621,
        "startup_baseline_uT": 769.1630154041171,
    },
}


def compile_replay(binary_path):
    subprocess.run(
        [
            "g++",
            "-std=c++17",
            "-Wall",
            "-Wextra",
            f"-I{INCLUDE_DIR}",
            str(REPLAY_SRC),
            str(DETECTOR_SRC),
            "-o",
            str(binary_path),
        ],
        cwd=REPO_ROOT,
        check=True,
    )


def parse_summary(output):
    for line in output.splitlines():
        if not line.startswith("SUMMARY,"):
            continue
        parts = line.split(",")
        return {parts[i]: float(parts[i + 1]) for i in range(1, len(parts), 2)}
    raise AssertionError("replay output did not include SUMMARY line")


class LoadStepDetectorReplayTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmpdir = tempfile.TemporaryDirectory()
        cls.binary = Path(cls.tmpdir.name) / "gm1_replay_load_step_detector"
        compile_replay(cls.binary)

    @classmethod
    def tearDownClass(cls):
        cls.tmpdir.cleanup()

    def replay(self, csv_name):
        csv_path = REPO_ROOT / "Rig_tests_and_analysis" / csv_name
        completed = subprocess.run(
            [str(self.binary), str(csv_path)],
            cwd=REPO_ROOT,
            check=True,
            text=True,
            stdout=subprocess.PIPE,
        )
        return completed.stdout

    def test_hassan_recordings_match_notebook_reference(self):
        for csv_name, expected in EXPECTED.items():
            with self.subTest(csv_name=csv_name):
                summary = parse_summary(self.replay(csv_name))
                self.assertEqual(int(summary["rows"]), expected["rows"])
                self.assertEqual(int(summary["events"]), expected["events"])
                self.assertAlmostEqual(summary["max_force_kg"], expected["max_force_kg"], places=3)
                self.assertAlmostEqual(
                    summary["startup_baseline_uT"],
                    expected["startup_baseline_uT"],
                    places=3,
                )


if __name__ == "__main__":
    unittest.main()
