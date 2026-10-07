#include "engine.hpp"
#include <android/native_window_jni.h>
#include <jni.h>
#include <unordered_map>

namespace {
std::mutex registry_mutex;
std::unordered_map<jlong,std::shared_ptr<thermal::Engine>> registry;
jlong next_id=1;
std::shared_ptr<thermal::Engine> engine(jlong id) {
    std::lock_guard<std::mutex> lock(registry_mutex);
    auto it=registry.find(id);if(it==registry.end())throw std::runtime_error("Native engine is closed");return it->second;
}
void fail(JNIEnv* env,const std::exception& error){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),error.what());}
}
#define JNI_METHOD(name) Java_com_thereprocase_thermalfield_NativeBridge_##name
extern "C" JNIEXPORT void JNICALL JNI_METHOD(archive)(JNIEnv* env,jobject,jlong id,jbyteArray data,jlong timestamp,jstring source,jint gain,jstring firmware,jintArray original,jintArray configured) {
    try{std::vector<std::uint8_t> bytes(env->GetArrayLength(data));env->GetByteArrayRegion(data,0,bytes.size(),reinterpret_cast<jbyte*>(bytes.data()));
        const char* chars=env->GetStringUTFChars(source,nullptr);std::string origin(chars);env->ReleaseStringUTFChars(source,chars);
        chars=env->GetStringUTFChars(firmware,nullptr);std::string version(chars);env->ReleaseStringUTFChars(firmware,chars);
        std::vector<int> before(env->GetArrayLength(original)),after(env->GetArrayLength(configured));
        env->GetIntArrayRegion(original,0,before.size(),before.data());env->GetIntArrayRegion(configured,0,after.size(),after.data());
        engine(id)->archive(bytes,timestamp,origin,gain,version,before,after);
    }catch(const std::exception& e){fail(env,e);}
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(restoreMeasurements)(JNIEnv* env,jobject,jlong id,jintArray data,jint first,jint second,jint isotherm,jfloat lower,jfloat upper,jint next) {
    try{std::vector<int> geometry(env->GetArrayLength(data));env->GetIntArrayRegion(data,0,geometry.size(),geometry.data());engine(id)->restore_measurements(geometry,first,second,isotherm,lower,upper,next);}catch(const std::exception& e){fail(env,e);}
}
extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(measurementState)(JNIEnv* env,jobject,jlong id) {
    try { return env->NewStringUTF(engine(id)->measurement_state().c_str()); }
    catch (const std::exception& e) { fail(env,e); return nullptr; }
}
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(measurementVersion)(JNIEnv* env,jobject,jlong id) {
    try{return engine(id)->measurement_version();}catch(const std::exception& e){fail(env,e);return 0;}
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(beginSavedProfile)(JNIEnv* env,jobject,jlong id) {
    try { engine(id)->begin_saved_profile(); } catch (const std::exception& e) { fail(env,e); }
}
extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(restoreLiveProfile)(JNIEnv* env,jobject,jlong id) {
    try { return env->NewStringUTF(engine(id)->restore_live_profile().c_str()); }
    catch (const std::exception& e) { fail(env,e); return nullptr; }
}
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(geometry)(JNIEnv* env,jobject,jlong id,jint measurement,jint kind,jdouble x0,jdouble y0,jdouble x1,jdouble y1,jint expected_rotation,jboolean expected_mirror) {
    try{return engine(id)->geometry(measurement,kind,x0,y0,x1,y1,expected_rotation,expected_mirror);}catch(const std::exception& e){fail(env,e);return 0;}
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(eraseGeometry)(JNIEnv* env,jobject,jlong id,jint measurement) {
    try{engine(id)->erase_geometry(measurement);}catch(const std::exception& e){fail(env,e);}
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(measurementOptions)(JNIEnv* env,jobject,jlong id,jint first,jint second,jint isotherm,jfloat lower,jfloat upper) {
    try{engine(id)->measurement_options(first,second,isotherm,lower,upper);}catch(const std::exception& e){fail(env,e);}
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(correction)(JNIEnv* env,jobject,jlong id,jdouble emissivity,jdouble reflected,jboolean corrected) {
    try{engine(id)->correction(emissivity,reflected,corrected);}catch(const std::exception& e){fail(env,e);}
}
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(create)(JNIEnv* env,jobject) {
    try{auto value=std::make_shared<thermal::Engine>();std::lock_guard<std::mutex> lock(registry_mutex);auto id=next_id++;registry.emplace(id,value);return id;}catch(const std::exception& e){fail(env,e);return 0;}
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(destroy)(JNIEnv*,jobject,jlong id) {
    std::shared_ptr<thermal::Engine> value;{std::lock_guard<std::mutex> lock(registry_mutex);auto it=registry.find(id);if(it==registry.end())return;value=it->second;registry.erase(it);}value->cancel();
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(surface)(JNIEnv* env,jobject,jlong id,jobject surface) {
    try{engine(id)->set_surface(surface ? ANativeWindow_fromSurface(env,surface) : nullptr);}catch(const std::exception& e){fail(env,e);}
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(configure)(JNIEnv* env,jobject,jlong id,jint palette,jboolean flip,jint rotation,jboolean mirror,jboolean automatic,jfloat lower,jfloat upper,jboolean preview_mirror,jint mounting,jint screen_rotation) {
    try{engine(id)->configure(palette,flip,rotation,mirror,automatic,lower,upper,preview_mirror,mounting,screen_rotation);}catch(const std::exception& e){fail(env,e);}
}
extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(open)(JNIEnv* env,jobject,jlong id,jint fd,jboolean high_gain) {
    try{return env->NewStringUTF(engine(id)->open(fd,high_gain).c_str());}catch(const std::exception& e){fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(replay)(JNIEnv* env,jobject,jlong id,jbyteArray data) {
    try{std::vector<std::uint8_t> bytes(env->GetArrayLength(data));env->GetByteArrayRegion(data,0,bytes.size(),reinterpret_cast<jbyte*>(bytes.data()));engine(id)->replay(bytes);}catch(const std::exception& e){fail(env,e);}
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(beginNetwork)(JNIEnv* env,jobject,jlong id) {
    try{engine(id)->begin_network();}catch(const std::exception& e){fail(env,e);}
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(networkFrame)(JNIEnv* env,jobject,jlong id,jbyteArray data,jlong sequence) {
    try{std::vector<std::uint8_t> bytes(env->GetArrayLength(data));env->GetByteArrayRegion(data,0,bytes.size(),reinterpret_cast<jbyte*>(bytes.data()));engine(id)->network_frame(bytes,static_cast<std::uint32_t>(sequence));}catch(const std::exception& e){fail(env,e);}
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(cancel)(JNIEnv* env,jobject,jlong id) {
    try{engine(id)->cancel();}catch(const std::exception& e){fail(env,e);}
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(stop)(JNIEnv* env,jobject,jlong id) {
    try{engine(id)->stop();}catch(const std::exception& e){fail(env,e);}
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(nuc)(JNIEnv* env,jobject,jlong id) {
    try{engine(id)->nuc();}catch(const std::exception& e){fail(env,e);}
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(gain)(JNIEnv* env,jobject,jlong id,jboolean high) {
    try{engine(id)->gain(high);}catch(const std::exception& e){fail(env,e);}
}
extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(summary)(JNIEnv* env,jobject,jlong id) {
    try{return env->NewStringUTF(engine(id)->summary().c_str());}catch(const std::exception& e){fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(dump)(JNIEnv* env,jobject,jlong id) {
    try{auto bytes=engine(id)->dump_frame();auto data=env->NewByteArray(bytes.size());env->SetByteArrayRegion(data,0,bytes.size(),reinterpret_cast<const jbyte*>(bytes.data()));return data;}catch(const std::exception& e){fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(snapshot)(JNIEnv* env,jobject,jlong id) {
    try{auto bytes=engine(id)->snapshot();auto data=env->NewByteArray(bytes.size());env->SetByteArrayRegion(data,0,bytes.size(),reinterpret_cast<const jbyte*>(bytes.data()));return data;}catch(const std::exception& e){fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT jbyteArray JNICALL JNI_METHOD(capture)(JNIEnv* env,jobject,jlong id) {
    try{auto bytes=engine(id)->capture();auto data=env->NewByteArray(bytes.size());env->SetByteArrayRegion(data,0,bytes.size(),reinterpret_cast<const jbyte*>(bytes.data()));return data;}catch(const std::exception& e){fail(env,e);return nullptr;}
}
