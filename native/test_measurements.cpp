#include "measurements.hpp"
#include <cmath>
#include <iostream>
#include <stdexcept>

void require(bool value,const char* message){if(!value)throw std::runtime_error(message);}
int main() {
    using namespace p2pro;
    BandPlanckTable planck;CorrectionTable raw(planck,1,20,false);
    std::array<std::uint16_t,256*192> plane{};
    for(unsigned y=0;y<192;++y)for(unsigned x=0;x<256;++x)plane[y*256+x]=18000+x+2*y;
    MeasurementSettings s;s.count=4;
    s.geometry[0]={1,MeasurementKind::Spot,0,0,0,0};s.geometry[1]={2,MeasurementKind::Spot,255,191,255,191};
    s.geometry[2]={3,MeasurementKind::Box,14,12,10,10};s.geometry[3]={4,MeasurementKind::Line,0,0,255,191};
    s.delta_first=2;s.delta_second=1;
    auto m=measure(plane,raw,s);
    require(m.results[2].valid==15 && m.results[2].invalid==0,"Inclusive reversed box bounds");
    require(std::abs(m.results[2].average-((18000+12+22)/64.0-273.15))<.0001,"Analytic box mean");
    require(m.results[2].min_index==10*256+10 && m.results[2].max_index==12*256+14,"Box extrema positions");
    require(std::abs(m.delta_celsius-637/64.0)<.0001,"Ordered spot delta");
    require(m.results[3].profile_count==256 && m.results[3].profile_indices[0]==0 && m.results[3].profile_indices[255]==49151,"Line includes both corners");
    s.geometry[3]={4,MeasurementKind::Line,255,191,0,0};auto reverse=measure(plane,raw,s);
    for(unsigned i=0;i<256;++i)require(m.results[3].profile_indices[i]==reverse.results[3].profile_indices[255-i],"Reversed line preserves sample path");
    s.geometry[3]={4,MeasurementKind::Line,5,6,5,6};require(measure(plane,raw,s).results[3].profile_count==1,"Degenerate line has one sample");
    s.isotherm_enabled=true;s.isotherm_lower=raw.temperature[18000];s.isotherm_upper=raw.temperature[18001];
    require(measure(plane,raw,s).isotherm_pixels==2,"Inclusive band and sensor samples");
    s.isotherm_mode=IsothermMode::Below;require(in_isotherm(s.isotherm_lower,s)&&!in_isotherm(s.isotherm_lower+1,s),"Below includes threshold");
    s.isotherm_mode=IsothermMode::Above;require(in_isotherm(s.isotherm_upper,s)&&!in_isotherm(s.isotherm_upper-1,s),"Above includes threshold");
    CorrectionTable partial(planck,.1,20,true);
    auto full=s;full.geometry[2]={3,MeasurementKind::Box,0,0,255,191};
    const auto corrected=measure(plane,partial,full);
    require(corrected.results[2].valid>0 && corrected.results[2].invalid>0 && corrected.results[2].valid+corrected.results[2].invalid==49152,"Correction validity precedes whole-box statistics");
    require(std::isnan(corrected.results[0].average) && std::isfinite(corrected.results[1].average) && std::isnan(corrected.delta_celsius),"Partly invalid corrected scene and delta");
    raw.temperature[plane[10*256+10]]=std::numeric_limits<float>::quiet_NaN();m=measure(plane,raw,s);
    require(m.results[2].valid==14 && m.results[2].invalid==1 && std::isfinite(m.results[2].average),"Invalid samples excluded from mean and extrema");
    raw.temperature[plane[0]]=std::numeric_limits<float>::quiet_NaN();require(std::isnan(measure(plane,raw,s).delta_celsius),"Invalid spot invalidates delta");
    for(auto& value:raw.temperature)value=std::numeric_limits<float>::quiet_NaN();m=measure(plane,raw,s);
    require(m.results[2].valid==0 && m.results[2].invalid==15 && std::isnan(m.results[2].minimum)&&std::isnan(m.results[2].average)&&m.isotherm_pixels==0,"All-invalid regions have no readings");
    bool rejected=false;s.geometry[0].x0=256;try{measure(plane,raw,s);}catch(const std::invalid_argument&){rejected=true;}
    require(rejected,"Out-of-bounds geometry rejected");
    s.geometry[0].x0=0;s.count=MaximumMeasurements+1;rejected=false;
    try{validate(s);}catch(const std::invalid_argument&){rejected=true;}require(rejected,"Measurement capacity is bounded");
    std::cout<<"Sensor spots, box statistics, line profiles, ordered delta, isotherms and invalid-sample checks passed\n";
}
