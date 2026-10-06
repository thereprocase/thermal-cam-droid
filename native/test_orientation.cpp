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
    auto clockwise=Orientation{1,false}.to_display({0,0});
    require(clockwise.x==1 && clockwise.y==0,"Clockwise top-left becomes top-right");
    auto mirrored=Orientation{0,true}.to_display({0,0});
    require(mirrored.x==1 && mirrored.y==0,"Horizontal mirror");
    std::cout << "Quarter-turn and mirror coordinate checks passed\n";
}
