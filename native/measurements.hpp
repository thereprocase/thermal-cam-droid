#pragma once
#include "radiometry.hpp"
#include <array>
#include <cstdint>
#include <limits>

namespace p2pro {
constexpr unsigned MaximumMeasurements = 16;
enum class MeasurementKind { Spot = 1, Box = 2, Line = 3 };
struct Geometry {
    unsigned id = 0;
    MeasurementKind kind = MeasurementKind::Spot;
    int x0 = 0, y0 = 0, x1 = 0, y1 = 0;
};
enum class IsothermMode { Band = 1, Below = 2, Above = 3 };
struct MeasurementSettings {
    std::array<Geometry,MaximumMeasurements> geometry{};
    unsigned count = 0;
    unsigned delta_first = 0, delta_second = 0;
    bool isotherm_enabled = false;
    IsothermMode isotherm_mode = IsothermMode::Band;
    float isotherm_lower = 20, isotherm_upper = 30;
};
struct MeasurementResult {
    unsigned valid = 0, invalid = 0;
    double minimum = std::numeric_limits<double>::quiet_NaN();
    double maximum = std::numeric_limits<double>::quiet_NaN();
    double average = std::numeric_limits<double>::quiet_NaN();
    unsigned min_index = 0, max_index = 0;
    unsigned profile_count = 0;
    std::array<float,256> profile{};
    std::array<unsigned,256> profile_indices{};
};
struct Measurements {
    std::array<MeasurementResult,MaximumMeasurements> results{};
    double delta_celsius = std::numeric_limits<double>::quiet_NaN();
    unsigned isotherm_pixels = 0;
};
void validate(const MeasurementSettings& settings);
bool in_isotherm(double temperature, const MeasurementSettings& settings);
// Geometry uses original sensor pixels, inclusive box edges and nearest-pixel
// line samples. The mean is the arithmetic mean of valid temperatures, not a
// mean radiance. No display interpolation or distance/area calibration applies.
Measurements measure(const std::array<std::uint16_t,256*192>& plane,
                     const CorrectionTable& correction, const MeasurementSettings& settings);
}
