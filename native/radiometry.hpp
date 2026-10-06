#pragma once
#include <array>
#include <cstdint>
#include <vector>

namespace p2pro {
// The band model assumes flat spectral response from 8–14 micrometres and
// unit atmospheric transmission at short range. These are model assumptions,
// not sensor-response or atmospheric measurements.
double band_radiance(double kelvin, unsigned intervals = 96);

class BandPlanckTable {
public:
    BandPlanckTable();
    double radiance(double kelvin) const;
    double temperature(double radiance) const;
private:
    static constexpr double Step = 0.25;
    static constexpr double MaximumKelvin = 1100;
    std::vector<double> radiance_;
};

struct CorrectionTable {
    double emissivity;
    double reflected_celsius;
    bool corrected;
    std::array<float,65536> temperature;
    CorrectionTable(const BandPlanckTable& planck, double emissivity,
                    double reflected_celsius, bool corrected);
};
} // namespace p2pro
