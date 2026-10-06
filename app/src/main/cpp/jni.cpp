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
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(create)(JNIEnv* env,jobject) {
    try{auto value=std::make_shared<thermal::Engine>();std::lock_guard<std::mutex> lock(registry_mutex);auto id=next_id++;registry.emplace(id,value);return id;}catch(const std::exception& e){fail(env,e);return 0;}
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(destroy)(JNIEnv*,jobject,jlong id) {
    std::shared_ptr<thermal::Engine> value;{std::lock_guard<std::mutex> lock(registry_mutex);auto it=registry.find(id);if(it==registry.end())return;value=it->second;registry.erase(it);}value->cancel();
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(surface)(JNIEnv* env,jobject,jlong id,jobject surface) {
    try{engine(id)->set_surface(surface ? ANativeWindow_fromSurface(env,surface) : nullptr);}catch(const std::exception& e){fail(env,e);}
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(configure)(JNIEnv* env,jobject,jlong id,jint palette,jboolean flip,jint rotation,jboolean mirror,jboolean automatic,jfloat lower,jfloat upper) {
    try{engine(id)->configure(palette,flip,rotation,mirror,automatic,lower,upper);}catch(const std::exception& e){fail(env,e);}
}
extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(open)(JNIEnv* env,jobject,jlong id,jint fd) {
    try{return env->NewStringUTF(engine(id)->open(fd).c_str());}catch(const std::exception& e){fail(env,e);return nullptr;}
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
