#include <cstdlib>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "LoadStepDetector.h"

namespace {

constexpr size_t kExpectedCols = 14;

bool parseCaptureRow(const std::string& line, std::vector<float>& values) {
  values.clear();
  if (line.empty() || line[0] == '#') return false;

  std::stringstream ss(line);
  std::string item;
  while (std::getline(ss, item, ',')) {
    char* end = nullptr;
    const float value = std::strtof(item.c_str(), &end);
    if (end == item.c_str()) return false;
    values.push_back(value);
  }
  return values.size() == kExpectedCols;
}

}  // namespace

int main(int argc, char** argv) {
  if (argc != 2) {
    std::cerr << "usage: replay_load_step_detector <capture.csv>\n";
    return 2;
  }

  std::ifstream in(argv[1]);
  if (!in) {
    std::cerr << "failed to open " << argv[1] << "\n";
    return 2;
  }

  LoadStepDetector detector;
  std::string line;
  std::vector<float> values;
  uint32_t first_timestamp_ms = 0;
  bool have_first_timestamp = false;
  size_t rows = 0;
  size_t events = 0;
  float max_force_kg = 0.0f;

  std::cout << std::fixed << std::setprecision(6);

  while (std::getline(in, line)) {
    if (!parseCaptureRow(line, values)) continue;

    const uint32_t seq = static_cast<uint32_t>(values[0]);
    const uint32_t timestamp_ms = static_cast<uint32_t>(values[1]);
    if (!have_first_timestamp) {
      first_timestamp_ms = timestamp_ms;
      have_first_timestamp = true;
    }
    const uint32_t relative_ms = timestamp_ms - first_timestamp_ms;
    const float mz_uT = values[13];

    const LoadStepSampleResult result = detector.process(seq, relative_ms, mz_uT);
    if (result.force_kg > max_force_kg) {
      max_force_kg = result.force_kg;
    }

    if (result.event_completed) {
      ++events;
      std::cout << "EVENT"
                << "," << LoadStepDetector::kindName(result.event.kind)
                << "," << result.event.start_ms
                << "," << result.event.release_ms
                << "," << result.event.confirm_ms
                << "," << result.event.duration_ms
                << "," << result.event.peak_force_kg
                << "," << result.event.peak_r_diff_mm
                << "," << result.event.peak_mag_delta_uT
                << "\n";
    }

    ++rows;
  }

  std::cout << "SUMMARY"
            << ",rows," << rows
            << ",events," << events
            << ",max_force_kg," << max_force_kg
            << ",startup_baseline_uT," << detector.startupBaselineUt()
            << ",startup_std_uT," << detector.startupStdUt()
            << "\n";
  return 0;
}
