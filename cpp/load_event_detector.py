"""Replayable magnetometer load-event detector.

The firmware implementation in GM1Firmware.ino should mirror this module.
It intentionally uses only the Python standard library so regression tests can
run before the notebook/scientific environment is installed.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import IntEnum
from pathlib import Path
from typing import Iterable


class LoadState(IntEnum):
    IDLE = 0
    LOADED = 2
    SUSTAINED = 3
    RELEASING = 4
    RECOVERING = 5


class EventCode(IntEnum):
    NONE = 0
    STEP = 1
    SUSTAINED = 2
    IGNORED = 3


@dataclass(frozen=True)
class DetectorParams:
    enter_dev_rel: float = 0.030
    exit_dev_rel: float = 0.010
    min_peak_dev_rel: float = 0.040
    sustained_time_ms: int = 2000
    release_confirm_ms: int = 100
    min_step_duration_ms: int = 100
    post_event_refractory_ms: int = 50
    idle_stable_samples: int = 5
    mag_lpf_alpha: float = 0.25
    baseline_alpha: float = 0.005
    baseline_distance_mm: float = 10.0
    force_slope: float = 658.7
    force_intercept: float = -173.5
    sample_period_ms: int = 20


@dataclass(frozen=True)
class MagSample:
    seq: int
    t_ms: int
    mx: float
    my: float
    mz: float


@dataclass(frozen=True)
class DetectorSample:
    seq: int
    t_ms: int
    b_abs_lpf: float
    b_0_abs: float
    dev_rel: float
    r_diff_mm: float
    force_n: float
    load_state: LoadState
    event_code: EventCode
    step_count: int
    sustained_count: int


@dataclass(frozen=True)
class LoadEvent:
    start_seq: int
    end_seq: int
    event_code: EventCode
    duration_ms: int
    peak_dev_rel: float


def parse_firmware_csv(path: str | Path) -> list[MagSample]:
    """Parse firmware CSV rows with magnetometer output enabled.

    The current firmware format always starts with seq,t_ms. If accel/gyro/
    orientation fields are disabled and magnetometer output is enabled, mx/my/mz
    are columns 2/3/4. This parser follows that recorded-data layout and skips
    comments or rows that do not contain those columns.
    """

    samples: list[MagSample] = []
    with Path(path).open() as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue

            parts = line.split(",")
            if len(parts) < 5:
                continue

            try:
                samples.append(
                    MagSample(
                        seq=int(parts[0]),
                        t_ms=int(float(parts[1])),
                        mx=float(parts[2]),
                        my=float(parts[3]),
                        mz=float(parts[4]),
                    )
                )
            except ValueError:
                continue

    return samples


def _cbrt(x: float) -> float:
    if x < 0.0:
        return -((-x) ** (1.0 / 3.0))
    return x ** (1.0 / 3.0)


def _force_from_r_diff(r_diff_mm: float, params: DetectorParams) -> float:
    if r_diff_mm <= 0.0:
        return 0.0
    return max(params.force_slope * r_diff_mm + params.force_intercept, 0.0)


class LoadEventDetector:
    """Stateful detector using baseline-relative filtered |mz| deviation."""

    def __init__(self, params: DetectorParams | None = None) -> None:
        self.params = params or DetectorParams()
        self.b_abs_lpf = 0.0
        self.b_0_abs = 0.0
        self.dev_rel = 0.0
        self.r_diff_mm = 0.0
        self.force_n = 0.0
        self.baseline_ready = False
        self.load_state = LoadState.IDLE
        self.idle_stable_count = 0
        self.load_start_ms = 0
        self.load_start_seq = 0
        self.release_start_ms = 0
        self.last_event_ms = -1_000_000_000
        self.peak_dev_rel = 0.0
        self.valid_peak = False
        self.is_sustained = False
        self.step_count = 0
        self.sustained_count = 0

    def update(self, seq: int, t_ms: int, mz: float) -> tuple[DetectorSample, LoadEvent | None]:
        params = self.params
        event_code = EventCode.NONE
        event: LoadEvent | None = None

        b_raw = abs(float(mz))
        if b_raw <= 1.0:
            return self._sample(seq, t_ms, event_code), None

        if not self.baseline_ready:
            self.b_abs_lpf = b_raw
            self.b_0_abs = b_raw
            self.baseline_ready = True
            return self._sample(seq, t_ms, event_code), None

        self.b_abs_lpf = (
            params.mag_lpf_alpha * b_raw
            + (1.0 - params.mag_lpf_alpha) * self.b_abs_lpf
        )

        self.dev_rel = (
            (self.b_abs_lpf - self.b_0_abs) / self.b_0_abs
            if self.b_0_abs > 1.0
            else 0.0
        )

        signed_r_diff = 0.0
        if self.b_abs_lpf > 1.0 and self.b_0_abs > 1.0:
            r_mm = params.baseline_distance_mm * _cbrt(self.b_0_abs / self.b_abs_lpf)
            signed_r_diff = params.baseline_distance_mm - r_mm
        self.r_diff_mm = max(signed_r_diff, 0.0)
        self.force_n = _force_from_r_diff(self.r_diff_mm, params)

        if self.load_state == LoadState.IDLE:
            if self.dev_rel >= params.enter_dev_rel:
                self._start_load(seq, t_ms)
            else:
                self._adapt_idle_baseline()

        elif self.load_state == LoadState.LOADED:
            self._update_peak()
            if (
                t_ms - self.load_start_ms >= params.sustained_time_ms
                and self.dev_rel > params.exit_dev_rel
            ):
                self.is_sustained = True
                self.load_state = LoadState.SUSTAINED
            elif self.dev_rel <= params.exit_dev_rel:
                self.release_start_ms = t_ms
                self.load_state = LoadState.RELEASING

        elif self.load_state == LoadState.SUSTAINED:
            self._update_peak()
            if self.dev_rel <= params.exit_dev_rel:
                self.release_start_ms = t_ms
                self.load_state = LoadState.RELEASING

        elif self.load_state == LoadState.RELEASING:
            if self.dev_rel > params.exit_dev_rel:
                self.load_state = LoadState.SUSTAINED if self.is_sustained else LoadState.LOADED
            elif t_ms - self.release_start_ms >= params.release_confirm_ms:
                event_code, event = self._complete_event(seq, t_ms)

        elif self.load_state == LoadState.RECOVERING:
            if t_ms - self.last_event_ms >= params.post_event_refractory_ms:
                if abs(self.dev_rel) < params.exit_dev_rel:
                    self.idle_stable_count += 1
                    if self.idle_stable_count >= params.idle_stable_samples:
                        self.idle_stable_count = 0
                        self.load_state = LoadState.IDLE
                else:
                    self.idle_stable_count = 0

        return self._sample(seq, t_ms, event_code), event

    def _start_load(self, seq: int, t_ms: int) -> None:
        self.load_state = LoadState.LOADED
        self.load_start_ms = t_ms
        self.load_start_seq = seq
        self.peak_dev_rel = self.dev_rel
        self.valid_peak = self.dev_rel >= self.params.min_peak_dev_rel
        self.is_sustained = False
        self.idle_stable_count = 0

    def _update_peak(self) -> None:
        if self.dev_rel > self.peak_dev_rel:
            self.peak_dev_rel = self.dev_rel
        if self.peak_dev_rel >= self.params.min_peak_dev_rel:
            self.valid_peak = True

    def _adapt_idle_baseline(self) -> None:
        params = self.params
        if abs(self.dev_rel) < params.exit_dev_rel:
            if self.idle_stable_count < params.idle_stable_samples:
                self.idle_stable_count += 1
            else:
                self.b_0_abs = (
                    params.baseline_alpha * self.b_abs_lpf
                    + (1.0 - params.baseline_alpha) * self.b_0_abs
                )
        else:
            self.idle_stable_count = 0

    def _complete_event(self, seq: int, t_ms: int) -> tuple[EventCode, LoadEvent]:
        duration_ms = self.release_start_ms - self.load_start_ms

        if not self.valid_peak:
            code = EventCode.IGNORED
        elif self.is_sustained or duration_ms >= self.params.sustained_time_ms:
            code = EventCode.SUSTAINED
            self.sustained_count += 1
        elif duration_ms >= self.params.min_step_duration_ms:
            code = EventCode.STEP
            self.step_count += 1
        else:
            code = EventCode.IGNORED

        event = LoadEvent(
            start_seq=self.load_start_seq,
            end_seq=seq,
            event_code=code,
            duration_ms=duration_ms,
            peak_dev_rel=self.peak_dev_rel,
        )

        self.load_state = LoadState.RECOVERING
        self.last_event_ms = t_ms
        self.b_0_abs = self.b_abs_lpf
        self.dev_rel = 0.0
        self.r_diff_mm = 0.0
        self.force_n = 0.0
        self.peak_dev_rel = 0.0
        self.valid_peak = False
        self.is_sustained = False
        self.idle_stable_count = 0

        return code, event

    def _sample(self, seq: int, t_ms: int, event_code: EventCode) -> DetectorSample:
        return DetectorSample(
            seq=seq,
            t_ms=t_ms,
            b_abs_lpf=self.b_abs_lpf,
            b_0_abs=self.b_0_abs,
            dev_rel=self.dev_rel,
            r_diff_mm=self.r_diff_mm,
            force_n=self.force_n,
            load_state=self.load_state,
            event_code=event_code,
            step_count=self.step_count,
            sustained_count=self.sustained_count,
        )


def replay_samples(
    samples: Iterable[MagSample],
    params: DetectorParams | None = None,
) -> tuple[list[DetectorSample], list[LoadEvent]]:
    detector = LoadEventDetector(params)
    sample_rows: list[DetectorSample] = []
    events: list[LoadEvent] = []

    for i, sample in enumerate(samples):
        t_ms = sample.t_ms if sample.t_ms is not None else i * detector.params.sample_period_ms
        row, event = detector.update(sample.seq, t_ms, sample.mz)
        sample_rows.append(row)
        if event is not None:
            events.append(event)

    return sample_rows, events


def replay_csv(
    path: str | Path,
    params: DetectorParams | None = None,
) -> tuple[list[DetectorSample], list[LoadEvent]]:
    return replay_samples(parse_firmware_csv(path), params)

