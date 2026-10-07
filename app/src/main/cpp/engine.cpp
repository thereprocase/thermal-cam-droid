#include "engine.hpp"
#include <android/log.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <deque>
#include <iomanip>
#include <libusb.h>
#include <limits>
#include <sstream>
#include <stdexcept>
#include "orientation.hpp"

namespace thermal {
std::int64_t monotonic_ns() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
std::string quote(const std::string& value) {
    std::ostringstream out; out << '"';
    for (unsigned char c : value) {
        if (c == '"' || c == '\\') out << '\\' << c;
        else if (c >= 32 && c < 127) out << c;
        else out << "\\u" << std::hex << std::setw(4) << std::setfill('0') << unsigned(c) << std::dec;
    }
    out << '"'; return out.str();
}

namespace {
std::string number(double value) {
    if(!std::isfinite(value))return "null";
    std::ostringstream out;out<<std::setprecision(12)<<value;return out.str();
}
std::string correction_metadata(const Frame& frame) {
    if(!frame.display.correction)return "";
    const auto& table=*frame.display.correction;
    std::ostringstream out;out<<std::setprecision(12)
        <<",\"emissivity\":"<<table.emissivity<<",\"reflected_apparent_celsius\":"<<table.reflected_celsius
        <<",\"correction_applied\":"<<(table.corrected ? "true":"false")
        <<",\"correction_model\":\"graybody Planck integral 8-14 um\",\"spectral_response_assumption\":\"flat\""
        <<",\"atmospheric_transmission_assumed\":1,\"invalid_pixels\":"<<frame.invalid_pixels;
    return out.str();
}
std::string measurement_metadata(const Frame& frame) {
    const auto& settings=frame.display.measurements;
    const unsigned rotation=(frame.display.rotation+(frame.display.flip ? 2:0))%4;
    p2pro::Orientation orientation{rotation,frame.display.mirror};
    auto point=[&](int x,int y){return orientation.to_display({(x+.5)/256,(y+.5)/192});};
    std::ostringstream out;out<<std::setprecision(12)<<",\"measurement_version\":"<<frame.display.measurement_version<<",\"measurements\":[";
    for(unsigned i=0;i<settings.count;++i) {
        if(i)out<<',';const auto& g=settings.geometry[i];const auto& r=frame.measurements.results[i];
        auto start=point(g.x0,g.y0),end=point(g.x1,g.y1);
        out<<"{\"id\":"<<g.id<<",\"kind\":"<<quote(g.kind==p2pro::MeasurementKind::Spot ? "spot":g.kind==p2pro::MeasurementKind::Box ? "box":"line")
           <<",\"sensor_start\":["<<g.x0<<','<<g.y0<<"],\"sensor_end\":["<<g.x1<<','<<g.y1<<']'
           <<",\"display_start\":["<<start.x<<','<<start.y<<"],\"display_end\":["<<end.x<<','<<end.y<<']'
           <<",\"minimum_celsius\":"<<number(r.minimum)<<",\"maximum_celsius\":"<<number(r.maximum)<<",\"average_celsius\":"<<number(r.average)
           <<",\"valid_samples\":"<<r.valid<<",\"invalid_samples\":"<<r.invalid<<",\"profile_celsius\":[";
        for(unsigned n=0;n<r.profile_count;++n){if(n)out<<',';out<<number(r.profile[n]);}
        out<<"],\"profile_sensor_indices\":[";
        for(unsigned n=0;n<r.profile_count;++n){if(n)out<<',';out<<r.profile_indices[n];}
        out<<"]}";
    }
    out<<"],\"delta_t\":{\"first\":"<<settings.delta_first<<",\"second\":"<<settings.delta_second<<",\"celsius\":"<<number(frame.measurements.delta_celsius)<<'}'
       <<",\"isotherm\":{\"enabled\":"<<(settings.isotherm_enabled ? "true":"false")<<",\"mode\":"<<quote(settings.isotherm_mode==p2pro::IsothermMode::Band ? "band":settings.isotherm_mode==p2pro::IsothermMode::Below ? "below":"above")
       <<",\"lower_celsius\":"<<settings.isotherm_lower<<",\"upper_celsius\":"<<settings.isotherm_upper<<",\"matched_pixels\":"<<frame.measurements.isotherm_pixels<<'}';
    return out.str();
}
class UsbTransport final : public p2pro::Transport {
public:
    UsbTransport(libusb_device_handle* handle, std::atomic<bool>& cancelled)
        : handle_(handle), cancelled_(cancelled) {}
    void write(std::uint16_t index, const std::vector<std::uint8_t>& data) override {
        auto bytes = data; transfer(false, index, bytes);
    }
    std::vector<std::uint8_t> read(std::uint16_t index, std::size_t length) override {
        std::vector<std::uint8_t> bytes(length); transfer(true, index, bytes); return bytes;
    }
private:
    void transfer(bool input, std::uint16_t index, std::vector<std::uint8_t>& bytes) {
        if (cancelled_.load()) throw std::runtime_error("Camera operation cancelled");
        int result = libusb_control_transfer(handle_, input ? 0xc1 : 0x41, input ? 0x44 : 0x45,
                                            0x78, index, bytes.data(), bytes.size(), 1000);
        if (result < 0) throw std::runtime_error(std::string("USB control: ") + libusb_error_name(result));
        if (result != static_cast<int>(bytes.size())) throw std::runtime_error("Short USB control transfer");
    }
    libusb_device_handle* handle_;
    std::atomic<bool>& cancelled_;
};

void check_uvc(int result, const char* operation) {
    if (result < 0) throw std::runtime_error(std::string(operation) + ": " + uvc_strerror(static_cast<uvc_error_t>(result)));
}

GLuint shader(GLenum type, const char* source) {
    GLuint result = glCreateShader(type); glShaderSource(result, 1, &source, nullptr); glCompileShader(result);
    GLint success = 0; glGetShaderiv(result, GL_COMPILE_STATUS, &success);
    if (!success) {
        char log[1024]{}; glGetShaderInfoLog(result, sizeof(log), nullptr, log); glDeleteShader(result);
        throw std::runtime_error(std::string("Shader: ") + log);
    }
    return result;
}

const char* vertex_source = R"GLSL(#version 300 es
out vec2 uv;
void main() {
    vec2 p = vec2((gl_VertexID & 1) == 0 ? -1.0 : 1.0, (gl_VertexID & 2) == 0 ? -1.0 : 1.0);
    gl_Position = vec4(p,0.0,1.0); uv = vec2((p.x+1.0)*0.5,(1.0-p.y)*0.5);
})GLSL";

const char* fragment_source = R"GLSL(#version 300 es
precision highp float;
precision highp usampler2D;
in vec2 uv;
out vec4 color;
uniform usampler2D rawPlane;
uniform highp sampler2D temperatureTable;
uniform vec2 limits;
uniform int palette;
uniform int rotation;
uniform bool mirrored;
uniform ivec2 minPoint;
uniform ivec2 maxPoint;
uniform int geometryCount;
uniform ivec4 geometry[16];
uniform int geometryKind[16];
uniform int isothermMode;
uniform vec2 isothermLimits;
vec3 ramp(float t) {
    if (palette == 1) return vec3(t);
    if (palette == 2) {
        vec3 a[6] = vec3[6](vec3(0,0,100),vec3(0,90,255),vec3(0,220,180),vec3(190,255,0),vec3(255,140,0),vec3(180,0,0));
        float p=t*5.0; int i=min(4,int(p)); return mix(a[i],a[i+1],p-float(i))/255.0;
    }
    vec3 a[6] = vec3[6](vec3(0),vec3(45,0,80),vec3(170,25,70),vec3(245,110,15),vec3(255,220,70),vec3(255));
    float p=t*5.0; int i=min(4,int(p)); return mix(a[i],a[i+1],p-float(i))/255.0;
}
bool crossAt(ivec2 p, ivec2 origin) {
    ivec2 d=abs(p-origin);return (d.x<=4 && d.y==0)||(d.y<=4 && d.x==0);
}
bool shapeAt(ivec2 p,ivec4 g,int kind,float width) {
    if(kind==1){ivec2 d=abs(p-g.xy);return (d.x<=5&&float(d.y)<=width)||(d.y<=5&&float(d.x)<=width);}
    if(kind==2){vec2 lo=vec2(min(g.xy,g.zw)),hi=vec2(max(g.xy,g.zw)),v=vec2(p);
        return all(greaterThanEqual(v,lo-vec2(width)))&&all(lessThanEqual(v,hi+vec2(width)))&&
            (abs(v.x-lo.x)<=width||abs(v.x-hi.x)<=width||abs(v.y-lo.y)<=width||abs(v.y-hi.y)<=width);}
    vec2 start=vec2(g.xy),delta=vec2(g.zw-g.xy);
    float t=clamp(dot(vec2(p)-start,delta)/max(dot(delta,delta),1.0),0.0,1.0);
    return length(vec2(p)-start-t*delta)<=width;
}
void main() {
    vec2 q=uv;
    if(mirrored)q.x=1.0-q.x;
    if(rotation==1)q=vec2(q.y,1.0-q.x);
    else if(rotation==2)q=vec2(1.0)-q;
    else if(rotation==3)q=vec2(1.0-q.y,q.x);
    ivec2 p=clamp(ivec2(q*vec2(256,192)),ivec2(0),ivec2(255,191));
    uint raw=texelFetch(rawPlane,p,0).r;
    float temperature=texelFetch(temperatureTable,ivec2(int(raw&255u),int(raw>>8)),0).r;
    bool valid=!(isnan(temperature)||isinf(temperature));
    float t=clamp((temperature-limits.x)/max(limits.y-limits.x,0.015625),0.0,1.0);
    vec3 rgb=valid ? ramp(t):vec3(0.75,0.0,0.75);
    bool band=valid&&((isothermMode==1&&temperature>=isothermLimits.x&&temperature<=isothermLimits.y)||
        (isothermMode==2&&temperature<=isothermLimits.x)||(isothermMode==3&&temperature>=isothermLimits.y));
    if(band)rgb=mix(rgb,vec3(0,1,1),0.65);
    for(int i=0;i<16;++i){if(i>=geometryCount)break;if(shapeAt(p,geometry[i],geometryKind[i],1.5))rgb=vec3(0);if(shapeAt(p,geometry[i],geometryKind[i],0.5))rgb=vec3(1);}
    if (crossAt(p,minPoint)) rgb=vec3(0.0,0.95,1.0);
    if (crossAt(p,maxPoint)) rgb=vec3(1.0,0.2,0.1);
    if (crossAt(p,ivec2(128,96))) rgb=vec3(1.0);
    color=vec4(rgb,1.0);
})GLSL";

struct Presentation {
    EGLuint64KHR frame_id;
    std::int64_t callback_ns;
};

class Renderer {
public:
    explicit Renderer(ANativeWindow* window) : window_(window) {
        try {
        display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (!eglInitialize(display_, nullptr, nullptr)) throw std::runtime_error("EGL initialization failed");
        const EGLint attrs[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
                                EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_NONE};
        EGLConfig config; EGLint count = 0;
        if (!eglChooseConfig(display_, attrs, &config, 1, &count) || !count) throw std::runtime_error("No ES3 EGL configuration");
        EGLint format=0;eglGetConfigAttrib(display_,config,EGL_NATIVE_VISUAL_ID,&format);
        if(ANativeWindow_setBuffersGeometry(window_,0,0,format)!=0)throw std::runtime_error("Thermal window buffer format unavailable");
        const EGLint ctx[] = {EGL_CONTEXT_CLIENT_VERSION,3,EGL_NONE};
        context_ = eglCreateContext(display_,config,EGL_NO_CONTEXT,ctx);
        surface_ = eglCreateWindowSurface(display_,config,window_,nullptr);
        if (context_ == EGL_NO_CONTEXT || surface_ == EGL_NO_SURFACE || !eglMakeCurrent(display_,surface_,surface_,context_)) {
            throw std::runtime_error("EGL Surface creation failed: "+std::to_string(eglGetError()));
        }
        GLuint vs = shader(GL_VERTEX_SHADER,vertex_source), fs = shader(GL_FRAGMENT_SHADER,fragment_source);
        program_ = glCreateProgram(); glAttachShader(program_,vs);glAttachShader(program_,fs);glLinkProgram(program_);
        glDeleteShader(vs);glDeleteShader(fs);
        GLint linked = 0;glGetProgramiv(program_,GL_LINK_STATUS,&linked);
        if (!linked) throw std::runtime_error("Thermal shader link failed");
        glGenVertexArrays(1,&vao_);glBindVertexArray(vao_);
        glGenTextures(1,&texture_);glBindTexture(GL_TEXTURE_2D,texture_);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        glTexStorage2D(GL_TEXTURE_2D,1,GL_R16UI,256,192);
        glGenTextures(1,&correction_texture_);glBindTexture(GL_TEXTURE_2D,correction_texture_);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glTexStorage2D(GL_TEXTURE_2D,1,GL_R32F,256,256);
        eglSwapInterval(display_,1);
        const char* extensions=eglQueryString(display_,EGL_EXTENSIONS);
        if (extensions && std::strstr(extensions,"EGL_ANDROID_get_frame_timestamps")) {
            next_frame_=reinterpret_cast<PFNEGLGETNEXTFRAMEIDANDROIDPROC>(eglGetProcAddress("eglGetNextFrameIdANDROID"));
            timestamps_=reinterpret_cast<PFNEGLGETFRAMETIMESTAMPSANDROIDPROC>(eglGetProcAddress("eglGetFrameTimestampsANDROID"));
            if (!eglSurfaceAttrib(display_,surface_,EGL_TIMESTAMPS_ANDROID,EGL_TRUE)) timestamps_=nullptr;
        }
        } catch (...) { shutdown(); throw; }
    }
    ~Renderer() { shutdown(); }
    void shutdown() {
        if (display_ != EGL_NO_DISPLAY) {
            if (context_ != EGL_NO_CONTEXT) {
                eglMakeCurrent(display_,surface_,surface_,context_);
                glDeleteTextures(1,&texture_);glDeleteTextures(1,&correction_texture_);glDeleteProgram(program_);glDeleteVertexArrays(1,&vao_);
            }
            eglMakeCurrent(display_,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
            if (surface_ != EGL_NO_SURFACE) eglDestroySurface(display_,surface_);
            if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_,context_);
            eglTerminate(display_);
            display_=EGL_NO_DISPLAY;context_=EGL_NO_CONTEXT;surface_=EGL_NO_SURFACE;
        }
    }
    double draw(const Frame& frame) {
        EGLint width=0,height=0;eglQuerySurface(display_,surface_,EGL_WIDTH,&width);eglQuerySurface(display_,surface_,EGL_HEIGHT,&height);
        glViewport(0,0,width,height);glClearColor(0.06f,0.06f,0.06f,1);glClear(GL_COLOR_BUFFER_BIT);
        const int rotation=(frame.display.rotation+(frame.display.flip ? 2:0))%4;
        const int image_width=rotation%2 ? 192:256,image_height=rotation%2 ? 256:192;
        int w=width,h=width*image_height/image_width;
        if (h>height) { h=height;w=height*image_width/image_height; }
        glViewport((width-w)/2,(height-h)/2,w,h);
        glUseProgram(program_);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,texture_);
        glPixelStorei(GL_UNPACK_ALIGNMENT,2);
        glTexSubImage2D(GL_TEXTURE_2D,0,0,0,256,192,GL_RED_INTEGER,GL_UNSIGNED_SHORT,frame.plane.data());
        glUniform1i(glGetUniformLocation(program_,"rawPlane"),0);
        bind_correction(frame);
        bind_measurements(frame);
        float lower=frame.display.automatic ? frame.minimum : frame.display.lower;
        float upper=frame.display.automatic ? frame.maximum : frame.display.upper;
        if(!std::isfinite(lower)||!std::isfinite(upper)){lower=0;upper=1;}
        glUniform2f(glGetUniformLocation(program_,"limits"),lower,upper);
        glUniform1i(glGetUniformLocation(program_,"palette"),frame.display.palette);
        glUniform1i(glGetUniformLocation(program_,"rotation"),rotation);
        glUniform1i(glGetUniformLocation(program_,"mirrored"),frame.display.mirror);
        glUniform2i(glGetUniformLocation(program_,"minPoint"),frame.min_index%256,frame.min_index/256);
        glUniform2i(glGetUniformLocation(program_,"maxPoint"),frame.max_index%256,frame.max_index/256);
        glBindVertexArray(vao_);glDrawArrays(GL_TRIANGLE_STRIP,0,4);
        EGLuint64KHR id=0;
        if (timestamps_ && next_frame_ && next_frame_(display_,surface_,&id)) {
            if(pending_.size()==64)pending_.pop_front();
            pending_.push_back({id,frame.callback_ns});
        }
        if (!eglSwapBuffers(display_,surface_)) throw std::runtime_error("EGL swap failed");
        return (monotonic_ns()-frame.callback_ns)/1e6;
    }
    std::vector<std::uint8_t> capture(const Frame& frame) {
        const int rotation=(frame.display.rotation+(frame.display.flip ? 2:0))%4;
        const int width=rotation%2 ? 192:256,height=rotation%2 ? 256:192;
        GLuint image=0,framebuffer=0;
        glGenTextures(1,&image);glBindTexture(GL_TEXTURE_2D,image);
        glTexStorage2D(GL_TEXTURE_2D,1,GL_RGBA8,width,height);
        glGenFramebuffers(1,&framebuffer);glBindFramebuffer(GL_FRAMEBUFFER,framebuffer);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,image,0);
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE) {
            glBindFramebuffer(GL_FRAMEBUFFER,0);glDeleteFramebuffers(1,&framebuffer);glDeleteTextures(1,&image);
            throw std::runtime_error("Capture framebuffer incomplete");
        }
        glViewport(0,0,width,height);glUseProgram(program_);glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,texture_);
        glTexSubImage2D(GL_TEXTURE_2D,0,0,0,256,192,GL_RED_INTEGER,GL_UNSIGNED_SHORT,frame.plane.data());
        glUniform1i(glGetUniformLocation(program_,"rawPlane"),0);
        bind_correction(frame);
        bind_measurements(frame);
        double lower=frame.display.automatic ? frame.minimum:frame.display.lower;
        double upper=frame.display.automatic ? frame.maximum:frame.display.upper;
        if(!std::isfinite(lower)||!std::isfinite(upper)){lower=0;upper=1;}
        glUniform2f(glGetUniformLocation(program_,"limits"),lower,upper);
        glUniform1i(glGetUniformLocation(program_,"palette"),frame.display.palette);
        glUniform1i(glGetUniformLocation(program_,"rotation"),rotation);
        glUniform1i(glGetUniformLocation(program_,"mirrored"),frame.display.mirror);
        glUniform2i(glGetUniformLocation(program_,"minPoint"),frame.min_index%256,frame.min_index/256);
        glUniform2i(glGetUniformLocation(program_,"maxPoint"),frame.max_index%256,frame.max_index/256);
        glBindVertexArray(vao_);glDrawArrays(GL_TRIANGLE_STRIP,0,4);
        std::vector<std::uint8_t> bytes(width*height*4);
        glPixelStorei(GL_PACK_ALIGNMENT,1);glReadPixels(0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,bytes.data());
        const GLenum error=glGetError();
        glBindFramebuffer(GL_FRAMEBUFFER,0);glDeleteFramebuffers(1,&framebuffer);glDeleteTextures(1,&image);
        if(error!=GL_NO_ERROR)throw std::runtime_error("Capture pixel readback failed");
        return bytes;
    }
    std::vector<double> presentations() {
        std::vector<double> result;
        while (timestamps_ && !pending_.empty()) {
            EGLint name=EGL_DISPLAY_PRESENT_TIME_ANDROID;EGLnsecsANDROID stamp=EGL_TIMESTAMP_PENDING_ANDROID;
            if (!timestamps_(display_,surface_,pending_.front().frame_id,1,&name,&stamp)) {pending_.pop_front();continue;}
            if (stamp==EGL_TIMESTAMP_PENDING_ANDROID) break;
            if (stamp>0) result.push_back((stamp-pending_.front().callback_ns)/1e6);
            pending_.pop_front();
        }
        return result;
    }
private:
    void bind_measurements(const Frame& frame) {
        const auto& s=frame.display.measurements;
        std::array<GLint,64> points{};std::array<GLint,16> kinds{};
        for(unsigned i=0;i<s.count;++i){const auto& g=s.geometry[i];points[i*4]=g.x0;points[i*4+1]=g.y0;points[i*4+2]=g.x1;points[i*4+3]=g.y1;kinds[i]=static_cast<int>(g.kind);}
        glUniform1i(glGetUniformLocation(program_,"geometryCount"),s.count);
        glUniform4iv(glGetUniformLocation(program_,"geometry"),16,points.data());
        glUniform1iv(glGetUniformLocation(program_,"geometryKind"),16,kinds.data());
        glUniform1i(glGetUniformLocation(program_,"isothermMode"),s.isotherm_enabled ? static_cast<int>(s.isotherm_mode):0);
        glUniform2f(glGetUniformLocation(program_,"isothermLimits"),s.isotherm_lower,s.isotherm_upper);
    }
    void bind_correction(const Frame& frame) {
        glActiveTexture(GL_TEXTURE1);glBindTexture(GL_TEXTURE_2D,correction_texture_);
        // A frame retains its immutable table, including while capture runs
        // after the user changes inputs. Display and statistics share the LUT.
        if(correction_owner_!=frame.display.correction) {
            glTexSubImage2D(GL_TEXTURE_2D,0,0,0,256,256,GL_RED,GL_FLOAT,frame.display.correction->temperature.data());
            correction_owner_=frame.display.correction;
        }
        glUniform1i(glGetUniformLocation(program_,"temperatureTable"),1);
        glActiveTexture(GL_TEXTURE0);
    }
    ANativeWindow* window_;
    EGLDisplay display_=EGL_NO_DISPLAY;EGLContext context_=EGL_NO_CONTEXT;EGLSurface surface_=EGL_NO_SURFACE;
    GLuint texture_=0,correction_texture_=0,program_=0,vao_=0;
    std::shared_ptr<const p2pro::CorrectionTable> correction_owner_;
    PFNEGLGETNEXTFRAMEIDANDROIDPROC next_frame_=nullptr;
    PFNEGLGETFRAMETIMESTAMPSANDROIDPROC timestamps_=nullptr;
    std::deque<Presentation> pending_;
};
} // namespace

Engine::Engine() {
    settings_.correction=std::make_shared<p2pro::CorrectionTable>(planck_,1.0,20.0,false);
    render_thread_=std::thread(&Engine::render_loop,this);
}
Engine::~Engine() {
    stop();quitting_=true;condition_.notify_all();render_thread_.join();
    if (desired_window_) ANativeWindow_release(desired_window_);
}
void Engine::set_surface(ANativeWindow* window) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (desired_window_) ANativeWindow_release(desired_window_);
    desired_window_=window;++window_generation_;condition_.notify_all();
}
void Engine::configure(int palette,bool flip,int rotation,bool mirror,bool automatic,float lower,float upper) {
    if (palette<0 || palette>2 || rotation<0 || rotation>3 || !std::isfinite(lower) || !std::isfinite(upper) || upper<=lower) throw std::invalid_argument("Invalid display settings");
    std::lock_guard<std::mutex> lock(mutex_);
    settings_.palette=palette;settings_.flip=flip;settings_.rotation=rotation;settings_.mirror=mirror;
    settings_.automatic=automatic;settings_.lower=lower;settings_.upper=upper;
}
void Engine::correction(double emissivity,double reflected,bool corrected) {
    // Table integration/inversion runs on the command worker, outside the
    // capture lock. Each subsequently received frame snapshots the result.
    auto table=std::make_shared<p2pro::CorrectionTable>(planck_,emissivity,reflected,corrected);
    std::lock_guard<std::mutex> lock(mutex_);settings_.correction=std::move(table);
}
unsigned Engine::geometry(unsigned id,int kind,double x0,double y0,double x1,double y1) {
    for(double value : {x0,y0,x1,y1})if(!std::isfinite(value)||value<0||value>1)throw std::invalid_argument("Place measurements within the thermal image");
    std::lock_guard<std::mutex> lock(mutex_);
    auto candidate=settings_.measurements;
    unsigned index=candidate.count;
    if(id)for(unsigned i=0;i<candidate.count;++i)if(candidate.geometry[i].id==id){index=i;break;}
    if(id && index==candidate.count)throw std::invalid_argument("Measurement no longer exists");
    if(index==candidate.count){if(candidate.count==p2pro::MaximumMeasurements)throw std::invalid_argument("Maximum 16 measurements; remove one before adding another");++candidate.count;}
    p2pro::Orientation orientation{unsigned((settings_.rotation+(settings_.flip ? 2:0))%4),settings_.mirror};
    auto a=orientation.to_sensor({x0,y0}),b=orientation.to_sensor({x1,y1});
    const auto pixel=[](double normalized,int extent){return std::clamp(int(normalized*extent),0,extent-1);};
    auto& g=candidate.geometry[index];g={id ? id:next_geometry_id_,static_cast<p2pro::MeasurementKind>(kind),pixel(a.x,256),pixel(a.y,192),pixel(b.x,256),pixel(b.y,192)};
    if(g.kind==p2pro::MeasurementKind::Spot){g.x1=g.x0;g.y1=g.y0;}
    p2pro::validate(candidate);
    if(!id)++next_geometry_id_;settings_.measurements=candidate;++settings_.measurement_version;return g.id;
}
void Engine::erase_geometry(unsigned id) {
    std::lock_guard<std::mutex> lock(mutex_);auto& s=settings_.measurements;
    if(!id){s.count=0;s.delta_first=s.delta_second=0;}
    else {
        for(unsigned i=0;i<s.count;++i)if(s.geometry[i].id==id){for(unsigned j=i+1;j<s.count;++j)s.geometry[j-1]=s.geometry[j];--s.count;break;}
        if(s.delta_first==id)s.delta_first=0;if(s.delta_second==id)s.delta_second=0;
    }
    ++settings_.measurement_version;
}
void Engine::measurement_options(unsigned first,unsigned second,int isotherm,float lower,float upper) {
    std::lock_guard<std::mutex> lock(mutex_);auto candidate=settings_.measurements;
    candidate.delta_first=first;candidate.delta_second=second;candidate.isotherm_enabled=isotherm!=0;
    candidate.isotherm_mode=static_cast<p2pro::IsothermMode>(isotherm ? isotherm:1);
    candidate.isotherm_lower=lower;candidate.isotherm_upper=upper;p2pro::validate(candidate);
    settings_.measurements=candidate;++settings_.measurement_version;
}
std::uint64_t Engine::measurement_version(){std::lock_guard<std::mutex> lock(mutex_);return settings_.measurement_version;}
void Engine::cancel() {cancelled_=true;condition_.notify_all();}
void Engine::close_session() {
    cancelled_=true;
    if (replay_thread_.joinable()) replay_thread_.join();
    if (streaming_) {uvc_stop_streaming(device_);streaming_=false;}
    camera_.reset();transport_.reset();
    if (device_) {uvc_close(device_);device_=nullptr;}
    if (context_) {uvc_exit(context_);context_=nullptr;}
}
void Engine::reset_frames(bool fixture) {
    std::lock_guard<std::mutex> lock(mutex_);
    ++capture_generation_;
    while(queue_size_) {
        occupied_[queue_[queue_head_]]=false;queue_head_=(queue_head_+1)%4;--queue_size_;
    }
    queue_tail_=queue_head_;
    last_presented_=Frame{};
    fixture_=fixture;network_=false;archive_=false;archive_timestamp_=0;error_.clear();identity_="{}";gain_mode_=fixture ? -1:1;command_active_=false;
    received_=rendered_=malformed_=overflow_=0;first_callback_ns_=last_callback_ns_=last_change_ns_=0;
    source_sequence_gaps_=0;source_sequence_seen_=false;
    swap_latency_ms_=max_swap_latency_ms_=presentation_latency_ms_=0;presentation_samples_=0;
}
void Engine::stop() {cancel();std::lock_guard<std::mutex> lock(operation_mutex_);close_session();reset_frames(false);}

std::string Engine::open(int fd) {
    std::lock_guard<std::mutex> operation(operation_mutex_);close_session();cancelled_=false;
    reset_frames(false);
    try {
        libusb_set_option(nullptr,LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
        check_uvc(uvc_init(&context_,nullptr),"UVC initialization");
        check_uvc(uvc_wrap(fd,context_,&device_),"Wrap USB fd");
        uvc_stream_ctrl_t ctrl{};
        check_uvc(uvc_get_stream_ctrl_format_size(device_,&ctrl,UVC_FRAME_FORMAT_YUYV,256,384,25),"Negotiate raw stream");
        check_uvc(uvc_start_streaming(device_,&ctrl,callback,this,0),"Start raw stream");streaming_=true;
        transport_=std::make_unique<UsbTransport>(uvc_get_libusb_handle(device_),cancelled_);
        camera_=std::make_unique<p2pro::Camera>(*transport_);
        std::ostringstream identity;
        auto string_info=[this](unsigned item) {auto bytes=camera_->device_info(item);return std::string(bytes.begin(),std::find(bytes.begin(),bytes.end(),0));};
        identity << "{\"firmware\":" << quote(string_info(5)) << ",\"persistent_device_identifiers_included\":false,\"original_properties\":[";
        for (int i=0;i<6;++i) {if(i)identity<<',';identity<<camera_->property(static_cast<p2pro::Property>(i));}
        // This creates a repeatable register configuration, not an independently
        // calibrated physical emissivity baseline; preserve that distinction.
        const std::uint16_t baseline[]={32,300,300,128,128,1};
        for (int i=0;i<6;++i) {
            auto p=static_cast<p2pro::Property>(i);
            if (camera_->property(p)!=baseline[i]) camera_->set_property(p,baseline[i]);
        }
        identity << "],\"configured_properties\":[";
        for (int i=0;i<6;++i) {if(i)identity<<',';identity<<camera_->property(static_cast<p2pro::Property>(i));}
        identity << "],\"physical_baseline_verified\":false}";
        std::lock_guard<std::mutex> lock(mutex_);identity_=identity.str();return identity_;
    } catch (...) {close_session();throw;}
}

void Engine::replay(const std::vector<std::uint8_t>& bytes) {
    if (bytes.size()!=CompositeBytes) throw std::invalid_argument("Fixture must be a complete composite frame");
    std::lock_guard<std::mutex> operation(operation_mutex_);close_session();cancelled_=false;
    reset_frames(true);
    {std::lock_guard<std::mutex> lock(mutex_);identity_="{\"source\":\"synthetic fixture replay\"}";}
    replay_thread_=std::thread([this,bytes] {
        auto deadline=std::chrono::steady_clock::now();
        while(!cancelled_) {ingest(bytes.data(),bytes.size(),512);deadline+=std::chrono::milliseconds(40);std::this_thread::sleep_until(deadline);}
    });
}
void Engine::archive(const std::vector<std::uint8_t>& bytes,std::int64_t timestamp,const std::string& source,int gain) {
    if(bytes.size()!=CompositeBytes || timestamp<=0 || gain<-1 || gain>1)throw std::invalid_argument("Invalid saved capture");
    std::lock_guard<std::mutex> operation(operation_mutex_);close_session();cancelled_=false;reset_frames(false);
    {
        std::lock_guard<std::mutex> lock(mutex_);archive_=true;archive_timestamp_=timestamp;gain_mode_=gain;
        identity_="{\"source\":\"saved capture\",\"original_source_kind\":"+quote(source)+",\"original_timestamp_unix_ns\":"+std::to_string(timestamp)+",\"physical_baseline_verified\":false}";
    }
    // Repainting supports palette/correction edits. Repeated frames are a saved
    // plane, not new sensor acquisitions, and retain the original timestamp.
    replay_thread_=std::thread([this,bytes]{auto deadline=std::chrono::steady_clock::now();while(!cancelled_){ingest(bytes.data(),bytes.size(),512);deadline+=std::chrono::milliseconds(40);std::this_thread::sleep_until(deadline);}});
}
void Engine::restore_measurements(const std::vector<int>& geometry,unsigned first,unsigned second,int isotherm,float lower,float upper) {
    if(geometry.size()%6 || geometry.size()>p2pro::MaximumMeasurements*6)throw std::invalid_argument("Invalid saved measurement count");
    p2pro::MeasurementSettings candidate;candidate.count=geometry.size()/6;
    unsigned maximum=0;
    for(unsigned i=0;i<candidate.count;++i){const unsigned n=i*6;if(geometry[n]<=0 || geometry[n]==std::numeric_limits<int>::max())throw std::invalid_argument("Invalid saved measurement id");
        candidate.geometry[i]={unsigned(geometry[n]),static_cast<p2pro::MeasurementKind>(geometry[n+1]),geometry[n+2],geometry[n+3],geometry[n+4],geometry[n+5]};maximum=std::max(maximum,unsigned(geometry[n]));}
    candidate.delta_first=first;candidate.delta_second=second;candidate.isotherm_enabled=isotherm!=0;candidate.isotherm_mode=static_cast<p2pro::IsothermMode>(isotherm ? isotherm:1);candidate.isotherm_lower=lower;candidate.isotherm_upper=upper;p2pro::validate(candidate);
    std::lock_guard<std::mutex> lock(mutex_);settings_.measurements=candidate;next_geometry_id_=maximum+1;++settings_.measurement_version;
}
void Engine::begin_network() {
    std::lock_guard<std::mutex> operation(operation_mutex_);close_session();cancelled_=false;reset_frames(false);
    std::lock_guard<std::mutex> lock(mutex_);network_=true;gain_mode_=-1;
    identity_="{\"source\":\"network\",\"protocol\":\"thermal-field-v1\",\"physical_baseline_verified\":false}";
}
void Engine::network_frame(const std::vector<std::uint8_t>& bytes,std::uint32_t sequence) {
    if(cancelled_)return;
    if(bytes.size()!=CompositeBytes)throw std::invalid_argument("Invalid network composite frame size");
    {
        std::lock_guard<std::mutex> lock(mutex_);if(!network_)return;
        if(source_sequence_seen_){const std::uint32_t delta=sequence-last_source_sequence_;if(delta>1&&delta<0x80000000u)source_sequence_gaps_+=delta-1;}
        source_sequence_seen_=true;last_source_sequence_=sequence;
    }
    ingest(bytes.data(),bytes.size(),512);
}
void Engine::callback(uvc_frame_t* frame,void* user) {
    auto* engine=static_cast<Engine*>(user);
    if(engine->cancelled_)return;
    {
        std::lock_guard<std::mutex> lock(engine->mutex_);
        if(engine->source_sequence_seen_) {
            const std::uint32_t delta=frame->sequence-engine->last_source_sequence_;
            if(delta>1 && delta<0x80000000u)engine->source_sequence_gaps_+=delta-1;
        }
        engine->source_sequence_seen_=true;engine->last_source_sequence_=frame->sequence;
    }
    if(frame->width!=256 || frame->height!=384 || frame->frame_format!=UVC_FRAME_FORMAT_YUYV) {
        std::lock_guard<std::mutex> lock(engine->mutex_);++engine->malformed_;return;
    }
    engine->ingest(static_cast<const std::uint8_t*>(frame->data),frame->data_bytes,frame->step ? frame->step : 512);
}
void Engine::ingest(const std::uint8_t* bytes,std::size_t length,std::size_t stride) {
    const auto stamp=monotonic_ns();
    std::lock_guard<std::mutex> lock(mutex_);
    ++received_;
    if(!bytes || stride<512 || stride>length/384) {++malformed_;return;}
    if(!first_callback_ns_)first_callback_ns_=stamp;last_callback_ns_=stamp;
    int slot=-1;for(int i=0;i<4;++i)if(!occupied_[i]){slot=i;break;}
    if(slot<0){++overflow_;return;}
    Frame& frame=frames_[slot];frame.sequence=received_;frame.generation=capture_generation_;frame.callback_ns=stamp;frame.display=settings_;
    frame.utc_ns=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
    frame.fixture=fixture_;frame.network=network_;frame.archive=archive_;
    if(archive_)frame.utc_ns=archive_timestamp_;
    frame.gain=gain_mode_;frame.command_active=command_active_;
    for(unsigned y=0;y<384;++y)std::memcpy(frame.composite.data()+y*512,bytes+y*stride,512);
    std::uint64_t hash=14695981039346656037ULL;
    double minimum=std::numeric_limits<double>::infinity(),maximum=-minimum;
    frame.min_index=frame.max_index=0;frame.invalid_pixels=0;
    for(unsigned i=0;i<PixelCount;++i) {
        auto offset=98304+i*2;auto raw=std::uint16_t(frame.composite[offset])|std::uint16_t(frame.composite[offset+1]<<8);
        frame.plane[i]=raw;hash^=raw;hash*=1099511628211ULL;
        const double value=frame.display.correction->temperature[raw];
        if(!std::isfinite(value)){++frame.invalid_pixels;continue;}
        if(value<minimum){minimum=value;frame.min_index=i;}if(value>maximum){maximum=value;frame.max_index=i;}
    }
    if(hash!=last_hash_ || !last_change_ns_){last_hash_=hash;last_change_ns_=stamp;}
    frame.minimum=minimum;frame.maximum=maximum;
    frame.center=frame.display.correction->temperature[frame.plane[96*256+128]];
    frame.measurements=p2pro::measure(frame.plane,*frame.display.correction,frame.display.measurements);
    occupied_[slot]=true;queue_[queue_tail_]=slot;queue_tail_=(queue_tail_+1)%4;++queue_size_;condition_.notify_all();
}

void Engine::nuc(){
    std::lock_guard<std::mutex> operation(operation_mutex_);if(!camera_)throw std::runtime_error("NUC requires a connected camera");
    {std::lock_guard<std::mutex> lock(mutex_);command_active_=true;}
    try{camera_->nuc();}catch(...){std::lock_guard<std::mutex> lock(mutex_);command_active_=false;throw;}
    {std::lock_guard<std::mutex> lock(mutex_);command_active_=false;}
}
void Engine::gain(bool high){
    std::lock_guard<std::mutex> operation(operation_mutex_);if(!camera_)throw std::runtime_error("Gain requires a connected camera");
    {std::lock_guard<std::mutex> lock(mutex_);command_active_=true;gain_mode_=-1;}
    try{
        camera_->set_property(p2pro::Property::HighGain,high ? 1:0);
        auto readback=camera_->property(p2pro::Property::HighGain);
        if(readback!=(high ? 1:0))throw std::runtime_error("Gain readback differs from requested mode");
        std::lock_guard<std::mutex> lock(mutex_);gain_mode_=readback;command_active_=false;
    }catch(...){std::lock_guard<std::mutex> lock(mutex_);command_active_=false;throw;}
}

void Engine::render_loop() {
    std::unique_ptr<Renderer> renderer;ANativeWindow* window=nullptr;std::uint64_t generation=0;
    auto last_log=monotonic_ns();
    unsigned retry_attempts=0;std::int64_t retry_at=0;
    auto initialize_renderer=[&] {
        ++retry_attempts;
        try{renderer=std::make_unique<Renderer>(window);}
        catch(const std::exception& e){
            std::lock_guard<std::mutex> lock(mutex_);error_=e.what();
            retry_at=monotonic_ns()+100000000LL*(1u<<std::min(retry_attempts-1,3u));
        }
    };
    while(!quitting_) {
        int slot=-1;ANativeWindow* replacement=nullptr;bool replace=false;
        std::shared_ptr<CaptureRequest> capture_request;
        {
            std::unique_lock<std::mutex> lock(mutex_);
            condition_.wait_for(lock,std::chrono::milliseconds(16),[&]{return quitting_ || window_generation_!=generation || ((queue_size_ || !capture_requests_.empty()) && renderer);});
            if(quitting_)break;
            if(window_generation_!=generation) {
                generation=window_generation_;replacement=desired_window_;if(replacement)ANativeWindow_acquire(replacement);replace=true;
            } else if(queue_size_ && renderer) {
                slot=queue_[queue_head_];queue_head_=(queue_head_+1)%4;--queue_size_;
            } else if(!capture_requests_.empty() && renderer) {
                capture_request=capture_requests_.front();capture_requests_.erase(capture_requests_.begin());
            }
        }
        if(replace) {
            renderer.reset();if(window)ANativeWindow_release(window);window=replacement;
            retry_attempts=0;if(window)initialize_renderer();
            continue;
        }
        // Surface recreation can briefly leave a valid Java Surface without a
        // usable EGL window. Retry on this owner with a bound, rather than
        // leaving every later frame stranded after a transient failure.
        if(!renderer && window && retry_attempts<4 && monotonic_ns()>=retry_at){initialize_renderer();continue;}
        if(slot>=0) {
            try {
                bool current=false;
                {std::lock_guard<std::mutex> lock(mutex_);current=frames_[slot].generation==capture_generation_;}
                if(current) {
                    double latency=renderer->draw(frames_[slot]);
                    std::lock_guard<std::mutex> lock(mutex_);
                    if(frames_[slot].generation==capture_generation_) {
                        ++rendered_;last_presented_=frames_[slot];
                        error_.clear();
                        retry_attempts=0;
                        swap_latency_ms_=latency;max_swap_latency_ms_=std::max(max_swap_latency_ms_,latency);
                    }
                }
            } catch(const std::exception& e) {
                {std::lock_guard<std::mutex> lock(mutex_);error_=e.what();}
                renderer.reset();retry_at=monotonic_ns()+100000000LL;
            }
            {std::lock_guard<std::mutex> lock(mutex_);occupied_[slot]=false;}
        }
        if(capture_request) {
            std::lock_guard<std::mutex> lock(capture_request->mutex);
            try{capture_request->rgba=renderer->capture(capture_request->frame);}
            catch(const std::exception& error){capture_request->error=error.what();}
            capture_request->done=true;capture_request->condition.notify_all();
        }
        if(renderer) {
            auto latencies=renderer->presentations();
            std::lock_guard<std::mutex> lock(mutex_);
            for(double latency:latencies){presentation_latency_ms_=latency;++presentation_samples_;}
        }
        if(monotonic_ns()-last_log>1000000000LL) {
            auto text=summary(false);__android_log_print(ANDROID_LOG_INFO,"ThermalField","%s",text.c_str());last_log=monotonic_ns();
        }
    }
    renderer.reset();if(window)ANativeWindow_release(window);
}
std::string Engine::summary(bool include_measurements) {
    std::lock_guard<std::mutex> lock(mutex_);const auto now=monotonic_ns();
    double elapsed=(last_callback_ns_-first_callback_ns_)/1e9;
    std::ostringstream out;out<<std::setprecision(12)<<"{\"frame\":"<<last_presented_.sequence
        <<",\"source\":"<<quote(archive_ ? "archive":fixture_ ? "fixture" : network_ ? "network":"camera")<<",\"minimum\":"<<number(last_presented_.minimum)
        <<",\"maximum\":"<<number(last_presented_.maximum)<<",\"center\":"<<number(last_presented_.center)
        <<",\"received\":"<<received_<<",\"rendered\":"<<rendered_<<",\"malformed\":"<<malformed_<<",\"overflow\":"<<overflow_
        <<",\"source_sequence_gaps\":"<<source_sequence_gaps_
        <<",\"fps\":"<<(elapsed>0 ? (received_-1)/elapsed : 0)<<",\"frame_age_ms\":"<<(last_callback_ns_ ? (now-last_callback_ns_)/1e6 : -1)
        <<",\"unchanged_ms\":"<<(last_change_ns_ ? (now-last_change_ns_)/1e6 : -1)
        <<",\"callback_to_swap_ms\":"<<swap_latency_ms_<<",\"max_callback_to_swap_ms\":"<<max_swap_latency_ms_
        <<",\"presentation_latency_ms\":"<<presentation_latency_ms_<<",\"presentation_samples\":"<<presentation_samples_
        <<",\"rotation_degrees\":"<<((last_presented_.display.rotation+(last_presented_.display.flip ? 2:0))%4)*90
        <<",\"mirrored\":"<<(last_presented_.display.mirror ? "true":"false")
        <<correction_metadata(last_presented_)
        <<(include_measurements ? measurement_metadata(last_presented_):std::string{})
        <<",\"error\":"<<quote(error_)<<",\"identity\":"<<identity_<<'}';return out.str();
}
std::vector<std::uint8_t> Engine::dump_frame(){std::lock_guard<std::mutex> lock(mutex_);if(!last_presented_.sequence)throw std::runtime_error("No displayed frame yet");return {last_presented_.composite.begin(),last_presented_.composite.end()};}
std::vector<std::uint8_t> Engine::snapshot() {
    std::lock_guard<std::mutex> lock(mutex_);
    const Frame& frame=last_presented_;
    if(!frame.sequence)throw std::runtime_error("No displayed frame yet");
    std::ostringstream json;json<<std::setprecision(12)
        <<"{\"frame\":"<<frame.sequence<<",\"session_generation\":"<<frame.generation
        <<",\"timestamp_unix_ns\":"<<frame.utc_ns<<",\"callback_monotonic_ns\":"<<frame.callback_ns
        <<",\"timestamp_basis\":"<<quote(frame.archive ? "original saved frame":"receiver callback")<<",\"source\":"<<quote(frame.archive ? "archive":frame.fixture ? "fixture":frame.network ? "network":"camera")
        <<",\"width\":256,\"composite_height\":384,\"plane_height\":192,\"stride\":512,\"format\":\"YUYV\""
        <<",\"minimum_celsius\":"<<number(frame.minimum)<<",\"maximum_celsius\":"<<number(frame.maximum)<<",\"center_celsius\":"<<number(frame.center)
        <<",\"palette\":"<<frame.display.palette<<",\"automatic_span\":"<<(frame.display.automatic ? "true":"false")
        <<",\"lower_celsius\":"<<number(frame.display.automatic ? frame.minimum:frame.display.lower)
        <<",\"upper_celsius\":"<<number(frame.display.automatic ? frame.maximum:frame.display.upper)
        <<",\"rotation_degrees\":"<<((frame.display.rotation+(frame.display.flip ? 2:0))%4)*90
        <<",\"mirrored\":"<<(frame.display.mirror ? "true":"false")<<correction_metadata(frame)<<measurement_metadata(frame)<<",\"identity\":"<<identity_<<'}';
    const std::string metadata=json.str();const std::uint32_t size=metadata.size();
    std::vector<std::uint8_t> result;result.reserve(4+size+CompositeBytes);
    for(int shift : {24,16,8,0})result.push_back(static_cast<std::uint8_t>(size>>shift));
    result.insert(result.end(),metadata.begin(),metadata.end());result.insert(result.end(),frame.composite.begin(),frame.composite.end());return result;
}
std::vector<std::uint8_t> Engine::capture() {
    auto request=std::make_shared<CaptureRequest>();
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if(!last_presented_.sequence || !desired_window_)throw std::runtime_error("No displayed frame to capture");
        request->frame=last_presented_;request->identity=identity_;
        capture_requests_.push_back(request);condition_.notify_all();
    }
    {
        std::unique_lock<std::mutex> lock(request->mutex);
        if(!request->condition.wait_for(lock,std::chrono::seconds(5),[&]{return request->done;})) {
            lock.unlock();std::lock_guard<std::mutex> engine_lock(mutex_);
            capture_requests_.erase(std::remove(capture_requests_.begin(),capture_requests_.end(),request),capture_requests_.end());
            throw std::runtime_error("Capture renderer timeout");
        }
        if(!request->error.empty())throw std::runtime_error(request->error);
    }
    const Frame& frame=request->frame;
    const unsigned rotation=(frame.display.rotation+(frame.display.flip ? 2:0))%4;
    const int width=rotation%2 ? 192:256,height=rotation%2 ? 256:192;
    p2pro::Orientation orientation{rotation,frame.display.mirror};
    auto point=[&](unsigned index){return orientation.to_display({((index%256)+.5)/256,((index/256)+.5)/192});};
    auto minimum=point(frame.min_index),maximum=point(frame.max_index),center=point(96*256+128);
    std::ostringstream json;json<<std::setprecision(12)
        <<"{\"frame\":"<<frame.sequence<<",\"session_generation\":"<<frame.generation
        <<",\"timestamp_unix_ns\":"<<frame.utc_ns<<",\"callback_monotonic_ns\":"<<frame.callback_ns
        <<",\"timestamp_basis\":"<<quote(frame.archive ? "original saved frame":"receiver callback")<<",\"source\":"<<quote(frame.archive ? "archive":frame.fixture ? "fixture":frame.network ? "network":"camera")
        <<",\"rendered_width\":"<<width<<",\"rendered_height\":"<<height
        <<",\"minimum_celsius\":"<<number(frame.minimum)<<",\"maximum_celsius\":"<<number(frame.maximum)<<",\"center_celsius\":"<<number(frame.center)
        <<",\"min_display\":["<<minimum.x<<','<<minimum.y<<"],\"max_display\":["<<maximum.x<<','<<maximum.y
        <<"],\"center_display\":["<<center.x<<','<<center.y<<']'
        <<",\"palette\":"<<quote(frame.display.palette==0 ? "ironbow":frame.display.palette==1 ? "white_hot":"rainbow")
        <<",\"automatic_span\":"<<(frame.display.automatic ? "true":"false")
        <<",\"lower_celsius\":"<<number(frame.display.automatic ? frame.minimum:frame.display.lower)
        <<",\"upper_celsius\":"<<number(frame.display.automatic ? frame.maximum:frame.display.upper)
        <<",\"rotation_degrees\":"<<rotation*90<<",\"mirrored\":"<<(frame.display.mirror ? "true":"false")
        <<",\"gain_mode\":"<<quote(frame.gain<0 ? "unknown":frame.gain==0 ? "low":"high")
        <<",\"command_active\":"<<(frame.command_active ? "true":"false")
        <<",\"frame_age_at_snapshot_ms\":"<<(monotonic_ns()-frame.callback_ns)/1e6
        <<correction_metadata(frame)<<measurement_metadata(frame)<<",\"identity\":"<<request->identity<<'}';
    const std::string metadata=json.str();const std::uint32_t size=metadata.size();
    std::vector<std::uint8_t> result;result.reserve(4+size+CompositeBytes+request->rgba.size());
    for(int shift : {24,16,8,0})result.push_back(static_cast<std::uint8_t>(size>>shift));
    result.insert(result.end(),metadata.begin(),metadata.end());result.insert(result.end(),frame.composite.begin(),frame.composite.end());
    result.insert(result.end(),request->rgba.begin(),request->rgba.end());return result;
}
} // namespace thermal
