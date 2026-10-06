#pragma once
#include <stdexcept>

namespace p2pro {
struct Point { double x, y; };
struct Orientation {
    unsigned quarter_turns = 0;
    bool mirror = false;
    Point to_display(Point sensor) const {
        Point result;
        switch(quarter_turns%4) {
            case 0:result=sensor;break;
            case 1:result={1-sensor.y,sensor.x};break;
            case 2:result={1-sensor.x,1-sensor.y};break;
            default:result={sensor.y,1-sensor.x};break;
        }
        if(mirror)result.x=1-result.x;
        return result;
    }
    Point to_sensor(Point display) const {
        if(mirror)display.x=1-display.x;
        switch(quarter_turns%4) {
            case 0:return display;
            case 1:return {display.y,1-display.x};
            case 2:return {1-display.x,1-display.y};
            default:return {1-display.y,display.x};
        }
    }
};
} // namespace p2pro
