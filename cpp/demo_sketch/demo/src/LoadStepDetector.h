#pragma once

#include <cstddef>
#include <cstdint>

enum class LoadStepState : uint8_t {
  ZEROING,
  IDLE,
  LOADED,
  RELEASING,
};

enum class LoadStepKind : uint8_t {
  STEP,
  SUSTAINED,
};

struct LoadStepEvent {
  LoadStepKind kind = LoadStepKind::STEP;
  uint32_t start_ms = 0;
  uint32_t release_ms = 0;
  uint32_t confirm_ms = 0;
  uint32_t duration_ms = 0;
  float peak_mag_delta_uT = 0.0f;
  float peak_r_diff_mm = 0.0f;
  float peak_force_kg = 0.0f;
};

struct LoadStepSampleResult {
  LoadStepState state = LoadStepState::ZEROING;
  float detector_signal_uT = 0.0f;
  float detector_signal_lpf_uT = 0.0f;
  float baseline_mean_uT = 0.0f;
  float baseline_std_uT = 1.0f;
  float z_score = 0.0f;
  float mag_delta_uT = 0.0f;
  float dr_mm = 0.0f;
  float force_kg = 0.0f;
  bool event_completed = false;
  LoadStepEvent event;
};

class LoadStepDetector {
 public:
  static constexpr float kMagLpfAlpha = 0.25f;
  static constexpr uint32_t kStartupZeroMs = 1000;
  static constexpr size_t kBaselineWindowSamples = 50;
  static constexpr float kZEnter = 2.0f;
  static constexpr float kReleaseFraction = 0.1f;
  static constexpr uint32_t kReleaseConfirmMs = 50;
  static constexpr uint32_t kRefractoryMs = 50;
  static constexpr size_t kPostReleaseBaselineSamples = kBaselineWindowSamples;
  static constexpr uint32_t kSustainedCutoffMs = 5000;
  static constexpr float kStdFloor = 1.0f;
  static constexpr float kDbToDrMmPerUt = 0.009659183f;
  static constexpr float kForceQuadraticA = 182.467185f;
  static constexpr float kForceQuadraticB = 398.386356f;
  static constexpr float kNewtonsPerKgForce = 9.80665f;

  LoadStepDetector();

  void reset();
  LoadStepSampleResult process(uint32_t seq, uint32_t t_ms, float mz_uT);

  float startupBaselineUt() const { return startup_baseline_uT_; }
  float startupStdUt() const { return startup_std_uT_; }

  static const char* stateName(LoadStepState state);
  static const char* kindName(LoadStepKind kind);
  static float forceKgFromRDiffMm(float r_diff_mm);

 private:
  struct RollingWindow {
    void clear();
    void push(float value);
    size_t size() const { return size_; }
    float mean() const;
    float stddev() const;

    float values[kBaselineWindowSamples] = {};
    size_t size_ = 0;
    size_t next_ = 0;
  };

  void updateBaselineFromWindow();
  void clearReleaseSamples();
  void pushReleaseSample(float value);
  void resetAfterConfirmedRelease(float current_signal_lpf_uT);
  uint32_t elapsedMs(uint32_t t_ms) const;

  bool initialized_ = false;
  uint32_t first_sample_ms_ = 0;
  uint32_t last_release_ms_ = 0;
  bool has_last_release_ = false;

  bool lpf_initialized_ = false;
  float detector_signal_lpf_uT_ = 0.0f;

  RollingWindow baseline_window_;
  float baseline_mean_uT_ = 0.0f;
  float baseline_std_uT_ = kStdFloor;
  float startup_baseline_uT_ = 0.0f;
  float startup_std_uT_ = kStdFloor;

  LoadStepState state_ = LoadStepState::ZEROING;
  uint32_t load_start_ms_ = 0;
  uint32_t release_candidate_ms_ = 0;
  float baseline_at_entry_uT_ = 0.0f;
  float peak_signal_uT_ = 0.0f;
  float release_level_uT_ = 0.0f;
  size_t post_release_baseline_samples_ = 0;
  float release_samples_[8] = {};
  size_t release_sample_count_ = 0;
};
