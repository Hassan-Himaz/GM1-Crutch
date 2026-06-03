#include "LoadStepDetector.h"

#include <cmath>

namespace {

float maxFloat(float a, float b) {
  return a > b ? a : b;
}

}  // namespace

LoadStepDetector::LoadStepDetector() {
  reset();
}

void LoadStepDetector::reset() {
  initialized_ = false;
  first_sample_ms_ = 0;
  last_release_ms_ = 0;
  has_last_release_ = false;
  lpf_initialized_ = false;
  detector_signal_lpf_uT_ = 0.0f;
  baseline_window_.clear();
  baseline_mean_uT_ = 0.0f;
  baseline_std_uT_ = kStdFloor;
  startup_baseline_uT_ = 0.0f;
  startup_std_uT_ = kStdFloor;
  state_ = LoadStepState::ZEROING;
  load_start_ms_ = 0;
  release_candidate_ms_ = 0;
  baseline_at_entry_uT_ = 0.0f;
  peak_signal_uT_ = 0.0f;
  release_level_uT_ = 0.0f;
  post_release_baseline_samples_ = 0;
  clearReleaseSamples();
}

LoadStepSampleResult LoadStepDetector::process(uint32_t seq, uint32_t t_ms, float mz_uT) {
  (void)seq;

  if (!initialized_) {
    initialized_ = true;
    first_sample_ms_ = t_ms;
  }

  const float detector_signal_uT = fabsf(mz_uT) - kMagOffsetSubtractUt;
  if (!lpf_initialized_) {
    detector_signal_lpf_uT_ = detector_signal_uT;
    lpf_initialized_ = true;
  } else {
    detector_signal_lpf_uT_ =
        kMagLpfAlpha * detector_signal_uT + (1.0f - kMagLpfAlpha) * detector_signal_lpf_uT_;
  }

  LoadStepSampleResult result;
  result.detector_signal_uT = detector_signal_uT;
  result.detector_signal_lpf_uT = detector_signal_lpf_uT_;

  if (elapsedMs(t_ms) <= kStartupZeroMs) {
    baseline_window_.push(detector_signal_lpf_uT_);
    updateBaselineFromWindow();
    startup_baseline_uT_ = baseline_mean_uT_;
    startup_std_uT_ = baseline_std_uT_;
    state_ = LoadStepState::ZEROING;
    result.state = state_;
    result.baseline_mean_uT = baseline_mean_uT_;
    result.baseline_std_uT = baseline_std_uT_;
    result.mag_delta_uT = detector_signal_lpf_uT_ - baseline_mean_uT_;
    result.dr_mm = kDbToDrMmPerUt * result.mag_delta_uT;
    result.force_kg = forceKgFromRDiffMm(result.dr_mm);
    return result;
  }

  const float z = (detector_signal_lpf_uT_ - baseline_mean_uT_) / maxFloat(baseline_std_uT_, kStdFloor);

  if (state_ == LoadStepState::ZEROING) {
    state_ = LoadStepState::IDLE;
  }

  if (state_ == LoadStepState::IDLE) {
    const bool can_start =
        !has_last_release_ ||
        static_cast<int32_t>(t_ms - last_release_ms_) >= static_cast<int32_t>(kRefractoryMs);
    if (can_start && z >= kZEnter) {
      state_ = LoadStepState::LOADED;
      load_start_ms_ = t_ms;
      release_candidate_ms_ = 0;
      baseline_at_entry_uT_ = baseline_mean_uT_;
      peak_signal_uT_ = detector_signal_lpf_uT_;
      release_level_uT_ =
          baseline_at_entry_uT_ + kReleaseFraction * (peak_signal_uT_ - baseline_at_entry_uT_);
      post_release_baseline_samples_ = 0;
    } else if (post_release_baseline_samples_ > 0) {
      baseline_window_.push(detector_signal_lpf_uT_);
      updateBaselineFromWindow();
      --post_release_baseline_samples_;
    }
  } else if (state_ == LoadStepState::LOADED) {
    if (detector_signal_lpf_uT_ > peak_signal_uT_) {
      peak_signal_uT_ = detector_signal_lpf_uT_;
      release_level_uT_ =
          baseline_at_entry_uT_ + kReleaseFraction * (peak_signal_uT_ - baseline_at_entry_uT_);
    }

    if (detector_signal_lpf_uT_ <= release_level_uT_) {
      state_ = LoadStepState::RELEASING;
      release_candidate_ms_ = t_ms;
      clearReleaseSamples();
      pushReleaseSample(detector_signal_lpf_uT_);
    }
  } else if (state_ == LoadStepState::RELEASING) {
    if (detector_signal_lpf_uT_ > peak_signal_uT_) {
      peak_signal_uT_ = detector_signal_lpf_uT_;
      release_level_uT_ =
          baseline_at_entry_uT_ + kReleaseFraction * (peak_signal_uT_ - baseline_at_entry_uT_);
    }

    if (detector_signal_lpf_uT_ > release_level_uT_) {
      state_ = LoadStepState::LOADED;
      release_candidate_ms_ = 0;
      clearReleaseSamples();
    } else if (static_cast<int32_t>(t_ms - release_candidate_ms_) >=
               static_cast<int32_t>(kReleaseConfirmMs)) {
      pushReleaseSample(detector_signal_lpf_uT_);
      const uint32_t duration_ms = release_candidate_ms_ - load_start_ms_;
      const float peak_mag_delta_uT = peak_signal_uT_ - baseline_at_entry_uT_;
      const float peak_r_diff_mm = kDbToDrMmPerUt * peak_mag_delta_uT;

      result.event_completed = true;
      result.event.kind =
          duration_ms < kSustainedCutoffMs ? LoadStepKind::STEP : LoadStepKind::SUSTAINED;
      result.event.start_ms = load_start_ms_;
      result.event.release_ms = release_candidate_ms_;
      result.event.confirm_ms = t_ms;
      result.event.duration_ms = duration_ms;
      result.event.peak_mag_delta_uT = peak_mag_delta_uT;
      result.event.peak_r_diff_mm = peak_r_diff_mm;
      result.event.peak_force_kg = forceKgFromRDiffMm(peak_r_diff_mm);

      last_release_ms_ = t_ms;
      has_last_release_ = true;
      resetAfterConfirmedRelease(detector_signal_lpf_uT_);
    } else {
      pushReleaseSample(detector_signal_lpf_uT_);
    }
  }

  result.state = state_;
  result.baseline_mean_uT = baseline_mean_uT_;
  result.baseline_std_uT = baseline_std_uT_;
  result.z_score = z;
  result.mag_delta_uT = detector_signal_lpf_uT_ - baseline_mean_uT_;
  result.dr_mm = kDbToDrMmPerUt * result.mag_delta_uT;
  result.force_kg = forceKgFromRDiffMm(result.dr_mm);
  return result;
}

const char* LoadStepDetector::stateName(LoadStepState state) {
  switch (state) {
    case LoadStepState::ZEROING:
      return "zeroing";
    case LoadStepState::IDLE:
      return "idle";
    case LoadStepState::LOADED:
      return "loaded";
    case LoadStepState::RELEASING:
      return "releasing";
  }
  return "unknown";
}

const char* LoadStepDetector::kindName(LoadStepKind kind) {
  switch (kind) {
    case LoadStepKind::STEP:
      return "step";
    case LoadStepKind::SUSTAINED:
      return "sustained";
  }
  return "unknown";
}

float LoadStepDetector::forceKgFromRDiffMm(float r_diff_mm) {
  const float displacement_mm = r_diff_mm > 0.0f ? r_diff_mm : 0.0f;
  const float force_n =
      kForceQuadraticA * displacement_mm * displacement_mm + kForceQuadraticB * displacement_mm;
  return force_n / kNewtonsPerKgForce;
}

void LoadStepDetector::RollingWindow::clear() {
  size_ = 0;
  next_ = 0;
}

void LoadStepDetector::RollingWindow::push(float value) {
  values[next_] = value;
  next_ = (next_ + 1) % kBaselineWindowSamples;
  if (size_ < kBaselineWindowSamples) {
    ++size_;
  }
}

float LoadStepDetector::RollingWindow::mean() const {
  if (size_ == 0) return 0.0f;
  float sum = 0.0f;
  for (size_t i = 0; i < size_; ++i) {
    sum += values[i];
  }
  return sum / static_cast<float>(size_);
}

float LoadStepDetector::RollingWindow::stddev() const {
  if (size_ < 2) return LoadStepDetector::kStdFloor;
  const float m = mean();
  float sum_sq = 0.0f;
  for (size_t i = 0; i < size_; ++i) {
    const float d = values[i] - m;
    sum_sq += d * d;
  }
  return sqrtf(sum_sq / static_cast<float>(size_ - 1));
}

void LoadStepDetector::updateBaselineFromWindow() {
  baseline_mean_uT_ = baseline_window_.mean();
  baseline_std_uT_ = maxFloat(baseline_window_.stddev(), kStdFloor);
}

void LoadStepDetector::clearReleaseSamples() {
  release_sample_count_ = 0;
}

void LoadStepDetector::pushReleaseSample(float value) {
  if (release_sample_count_ < (sizeof(release_samples_) / sizeof(release_samples_[0]))) {
    release_samples_[release_sample_count_++] = value;
  }
}

void LoadStepDetector::resetAfterConfirmedRelease(float current_signal_lpf_uT) {
  baseline_window_.clear();
  if (release_sample_count_ > 0) {
    for (size_t i = 0; i < release_sample_count_; ++i) {
      baseline_window_.push(release_samples_[i]);
    }
  } else {
    baseline_window_.push(current_signal_lpf_uT);
  }
  updateBaselineFromWindow();
  post_release_baseline_samples_ =
      baseline_window_.size() < kPostReleaseBaselineSamples
          ? kPostReleaseBaselineSamples - baseline_window_.size()
          : 0;
  state_ = LoadStepState::IDLE;
  load_start_ms_ = 0;
  release_candidate_ms_ = 0;
  baseline_at_entry_uT_ = 0.0f;
  peak_signal_uT_ = 0.0f;
  release_level_uT_ = 0.0f;
  clearReleaseSamples();
}

uint32_t LoadStepDetector::elapsedMs(uint32_t t_ms) const {
  return t_ms - first_sample_ms_;
}
