#include "orientation.hpp"
#include <cmath>
#include <iostream>
#include <stdexcept>
void require(bool ok,const char* text){if(!ok)throw std::runtime_error(text);}
int main() {
    using namespace p2pro;
    for(unsigned turn=0;turn<4;++turn)for(bool mirror : {false,true}) {
        Orientation transform{turn,mirror};
        for(Point original : {Point{0,0},Point{1,1},Point{.5,.5},Point{.125,.75}}) {
            auto displayed=transform.to_display(original),restored=transform.to_sensor(displayed);
            require(std::abs(original.x-restored.x)<1e-12 && std::abs(original.y-restored.y)<1e-12,"Orientation inverse");
        }
    }
    for(unsigned turn=0;turn<4;++turn)for(bool export_mirror : {false,true}) {
        const Orientation output{turn,export_mirror};
        const auto preview=output.preview(true);
        const Point sensor{.125,.75};
        const auto saved=output.to_display(sensor),displayed=preview.to_display(sensor);
        require(std::abs(displayed.x-(1-saved.x))<1e-12 && displayed.y==saved.y,"Selfie preview reflects display x after rotation");
        const auto tapped=preview.to_sensor(displayed);
        require(std::abs(tapped.x-sensor.x)<1e-12 && std::abs(tapped.y-sensor.y)<1e-12,"Selfie taps recover sensor coordinates");
        require(output.mirror==export_mirror,"Preview does not mutate export mirror");
    }
    auto clockwise=Orientation{1,false}.to_display({0,0});
    require(clockwise.x==1 && clockwise.y==0,"Clockwise top-left becomes top-right");
    auto mirrored=Orientation{0,true}.to_display({0,0});
    require(mirrored.x==1 && mirrored.y==0,"Horizontal mirror");
    std::cout << "Quarter-turn and mirror coordinate checks passed\n";
}
