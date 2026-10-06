#include "radiometry.hpp"
#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace p2pro {
namespace {
constexpr double Planck = 6.62607015e-34;
constexpr double Light = 299792458.0;
constexpr double Boltzmann = 1.380649e-23;
double spectral_radiance(double wavelength, double kelvin) {
    if (kelvin==0) return 0;
    const double exponent = Planck*Light/(wavelength*Boltzmann*kelvin);
    if (exponent>700) return 0;
    return 2*Planck*Light*Light/(std::pow(wavelength,5)*std::expm1(exponent));
}
}

double band_radiance(double kelvin,unsigned intervals) {
    if (!std::isfinite(kelvin) || kelvin<0 || intervals<2 || intervals%2) {
        throw std::invalid_argument("Invalid Planck integration domain");
    }
    constexpr double lower=8e-6,upper=14e-6;
    const double step=(upper-lower)/intervals;
    double sum=spectral_radiance(lower,kelvin)+spectral_radiance(upper,kelvin);
    for(unsigned i=1;i<intervals;++i)sum+=(i%2 ? 4:2)*spectral_radiance(lower+i*step,kelvin);
    return sum*step/3;
}

BandPlanckTable::BandPlanckTable() {
    radiance_.reserve(static_cast<std::size_t>(MaximumKelvin/Step)+1);
    for(unsigned i=0;i<=MaximumKelvin/Step;++i)radiance_.push_back(band_radiance(i*Step));
}
double BandPlanckTable::radiance(double kelvin) const {
    if (!std::isfinite(kelvin) || kelvin<0 || kelvin>MaximumKelvin) return std::numeric_limits<double>::quiet_NaN();
    const double position=kelvin/Step;
    const auto lower=static_cast<std::size_t>(position);
    if(lower+1>=radiance_.size())return radiance_.back();
    return radiance_[lower]+(radiance_[lower+1]-radiance_[lower])*(position-lower);
}
double BandPlanckTable::temperature(double radiance) const {
    if(!std::isfinite(radiance) || radiance<=0 || radiance>radiance_.back())return std::numeric_limits<double>::quiet_NaN();
    auto upper=std::lower_bound(radiance_.begin(),radiance_.end(),radiance);
    const auto index=static_cast<std::size_t>(upper-radiance_.begin());
    if(!index)return 0;
    const double lower=radiance_[index-1],difference=*upper-lower;
    if(difference<=0)return std::numeric_limits<double>::quiet_NaN();
    return (index-1+(radiance-lower)/difference)*Step;
}

CorrectionTable::CorrectionTable(const BandPlanckTable& planck,double epsilon,double reflected,bool apply)
    : emissivity(epsilon),reflected_celsius(reflected),corrected(apply) {
    if(!std::isfinite(epsilon) || epsilon<=0 || epsilon>1 || !std::isfinite(reflected) || reflected+273.15<=0 || reflected+273.15>1100) {
        throw std::invalid_argument("Invalid emissivity or reflected temperature");
    }
    const double reflected_radiance=planck.radiance(reflected+273.15);
    for(unsigned raw=0;raw<temperature.size();++raw) {
        const double measured_kelvin=raw/64.0;
        double value=measured_kelvin-273.15;
        if(apply && epsilon!=1) {
            const double object_radiance=(planck.radiance(measured_kelvin)-(1-epsilon)*reflected_radiance)/epsilon;
            value=planck.temperature(object_radiance)-273.15;
        }
        temperature[raw]=static_cast<float>(value);
    }
}
} // namespace p2pro
