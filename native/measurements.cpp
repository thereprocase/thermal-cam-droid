#include "measurements.hpp"
#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace p2pro {
void validate(const MeasurementSettings& settings) {
    if(settings.count>MaximumMeasurements || !std::isfinite(settings.isotherm_lower) || !std::isfinite(settings.isotherm_upper) || settings.isotherm_upper<settings.isotherm_lower)
        throw std::invalid_argument("Invalid measurement settings");
    if(settings.isotherm_mode!=IsothermMode::Band && settings.isotherm_mode!=IsothermMode::Below && settings.isotherm_mode!=IsothermMode::Above)
        throw std::invalid_argument("Invalid isotherm mode");
    for(unsigned i=0;i<settings.count;++i) {
        const auto& g=settings.geometry[i];
        if(!g.id || g.x0<0 || g.x0>255 || g.x1<0 || g.x1>255 || g.y0<0 || g.y0>191 || g.y1<0 || g.y1>191 ||
           (g.kind!=MeasurementKind::Spot && g.kind!=MeasurementKind::Box && g.kind!=MeasurementKind::Line))
            throw std::invalid_argument("Measurement is outside sensor bounds");
        for(unsigned j=0;j<i;++j)if(settings.geometry[j].id==g.id)throw std::invalid_argument("Duplicate measurement id");
    }
}
bool in_isotherm(double temperature,const MeasurementSettings& s) {
    if(!s.isotherm_enabled || !std::isfinite(temperature))return false;
    if(s.isotherm_mode==IsothermMode::Below)return temperature<=s.isotherm_lower;
    if(s.isotherm_mode==IsothermMode::Above)return temperature>=s.isotherm_upper;
    return temperature>=s.isotherm_lower && temperature<=s.isotherm_upper;
}
Measurements measure(const std::array<std::uint16_t,256*192>& plane,const CorrectionTable& correction,const MeasurementSettings& settings) {
    validate(settings);
    Measurements measured;
    for(unsigned i=0;i<settings.count;++i) {
        const auto& g=settings.geometry[i];auto& result=measured.results[i];double sum=0;
        auto sample=[&](int x,int y,bool profile) {
            const unsigned index=y*256+x;const float value=correction.temperature[plane[index]];
            if(profile){result.profile[result.profile_count]=value;result.profile_indices[result.profile_count++]=index;}
            if(!std::isfinite(value)){++result.invalid;return;}
            if(!result.valid || value<result.minimum){result.minimum=value;result.min_index=index;}
            if(!result.valid || value>result.maximum){result.maximum=value;result.max_index=index;}
            ++result.valid;sum+=value;
        };
        if(g.kind==MeasurementKind::Spot)sample(g.x0,g.y0,false);
        else if(g.kind==MeasurementKind::Box) {
            for(int y=std::min(g.y0,g.y1);y<=std::max(g.y0,g.y1);++y)
                for(int x=std::min(g.x0,g.x1);x<=std::max(g.x0,g.x1);++x)sample(x,y,false);
        } else {
            const int steps=std::max(std::abs(g.x1-g.x0),std::abs(g.y1-g.y0));
            if(!steps)sample(g.x0,g.y0,true);
            else for(int n=0;n<=steps;++n) {
                // Integer rational rounding makes reversing the endpoints
                // reverse exactly the same sampled pixels, including ties.
                const int x=(g.x0*(steps-n)+g.x1*n+steps/2)/steps;
                const int y=(g.y0*(steps-n)+g.y1*n+steps/2)/steps;
                sample(x,y,true);
            }
        }
        if(result.valid)result.average=sum/result.valid;
    }
    double first=std::numeric_limits<double>::quiet_NaN(),second=first;
    for(unsigned i=0;i<settings.count;++i)if(settings.geometry[i].kind==MeasurementKind::Spot) {
        if(settings.geometry[i].id==settings.delta_first)first=measured.results[i].average;
        if(settings.geometry[i].id==settings.delta_second)second=measured.results[i].average;
    }
    if(settings.delta_first && settings.delta_second && settings.delta_first!=settings.delta_second)measured.delta_celsius=first-second;
    if(settings.isotherm_enabled)for(auto word:plane)if(in_isotherm(correction.temperature[word],settings))++measured.isotherm_pixels;
    return measured;
}
}
