#include "radiometry.hpp"
#include <cmath>
#include <iostream>
#include <stdexcept>

void require(bool ok,const char* message){if(!ok)throw std::runtime_error(message);}
int main() {
    using namespace p2pro;
    BandPlanckTable table;
    for(double kelvin : {230.125,273.15,300.0,328.15,393.15,873.15}) {
        const double reference=band_radiance(kelvin,768);
        require(std::abs(band_radiance(kelvin)-reference)/reference<1e-7,"Quadrature convergence");
        require(std::abs(table.temperature(table.radiance(kelvin))-kelvin)<1e-8,"Table round trip");
        require(std::abs(table.temperature(reference)-kelvin)<0.001,"Interpolation temperature error");
    }
    CorrectionTable identity(table,1,20,true),raw(table,.96,20,false),corrected(table,.96,20,true);
    for(unsigned word : {16000,17482,19052,21002,30000,60000}) {
        require(std::abs(identity.temperature[word]-(word/64.0-273.15))<0.0001,"Emissivity-one identity");
        require(identity.temperature[word]==raw.temperature[word],"Raw toggle identity");
    }
    for(double object_c : {0.0,20.0,55.0,90.0}) {
        const double measured=table.temperature(.96*band_radiance(object_c+273.15,768)+.04*band_radiance(293.15,768));
        const auto word=static_cast<unsigned>(std::lround(measured*64));
        require(std::abs(corrected.temperature[word]-object_c)<0.02,"Graybody synthetic recovery");
    }
    require(std::isnan(corrected.temperature[0]),"Negative solved radiance is invalid");
    // Cold reflected sky and sub-zero objects are normal enclosure inputs.
    // Compare the quantized-word lookup to finer quadrature plus bisection,
    // separately from the error introduced by the camera's 1/64 K words.
    for(double reflected_c : {-60.0,-20.0,20.0,60.0}) {
        for(double epsilon : {.5,.9,.96}) {
            CorrectionTable cold(table,epsilon,reflected_c,true);
            for(double object_c : {-20.0,0.0,55.0}) {
                const double reflection=band_radiance(reflected_c+273.15,768);
                const double measured=table.temperature(epsilon*band_radiance(object_c+273.15,768)+(1-epsilon)*reflection);
                const auto word=static_cast<unsigned>(std::lround(measured*64));
                const double target=(band_radiance(word/64.0,768)-(1-epsilon)*reflection)/epsilon;
                double lower=0,upper=1100;
                for(unsigned i=0;i<48;++i){const double middle=(lower+upper)/2;if(band_radiance(middle,768)<target)lower=middle;else upper=middle;}
                const double reference=(lower+upper)/2-273.15;
                require(std::abs(cold.temperature[word]-reference)<.001,"Cold-scene independent inverse agreement");
                require(std::abs(cold.temperature[word]-object_c)<.05,"Cold-scene quantized synthetic recovery");
            }
        }
    }
    for(double epsilon : {0.0,-.1,1.1}) {
        bool rejected=false;try{CorrectionTable invalid(table,epsilon,20,true);}catch(const std::invalid_argument&){rejected=true;}
        require(rejected,"Reject invalid emissivity");
    }
    std::cout << "Band Planck integration, inversion and graybody checks passed\n";
}
