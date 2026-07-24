#include <jni.h>
#include <string>
#include <android/log.h>
#include "audio_engine.h"

#define LOG_TAG "ChordHelper-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace chordhelper;

extern "C" {

// ============================================================================
// Drum Synthesis JNI Functions
// ============================================================================

JNIEXPORT void JNICALL
Java_de_metaviewsoft_chordprogressionhelper_util_NativeAudio_addKick(
    JNIEnv* env, jobject /* this */, jdoubleArray buffer, jint duration,
    jdouble levelScale, jdouble envelopeScale, jdouble drumLevel) {
    
    jdouble* bufferPtr = env->GetDoubleArrayElements(buffer, nullptr);
    if (bufferPtr == nullptr) {
        LOGE("Failed to get buffer array");
        return;
    }
    
    AudioEngine::addKick(bufferPtr, duration, levelScale, envelopeScale, drumLevel);
    
    env->ReleaseDoubleArrayElements(buffer, bufferPtr, 0);
}

JNIEXPORT void JNICALL
Java_de_metaviewsoft_chordprogressionhelper_util_NativeAudio_addSnare(
    JNIEnv* env, jobject /* this */, jdoubleArray buffer, jint duration,
    jdouble levelScale, jdouble envelopeScale, jdouble drumLevel) {
    
    jdouble* bufferPtr = env->GetDoubleArrayElements(buffer, nullptr);
    if (bufferPtr == nullptr) {
        LOGE("Failed to get buffer array");
        return;
    }
    
    AudioEngine::addSnare(bufferPtr, duration, levelScale, envelopeScale, drumLevel);
    
    env->ReleaseDoubleArrayElements(buffer, bufferPtr, 0);
}

JNIEXPORT void JNICALL
Java_de_metaviewsoft_chordprogressionhelper_util_NativeAudio_addHiHat(
    JNIEnv* env, jobject /* this */, jdoubleArray buffer, jint duration,
    jdouble levelScale, jdouble envelopeScale, jdouble hiHatHighpass) {
    
    jdouble* bufferPtr = env->GetDoubleArrayElements(buffer, nullptr);
    if (bufferPtr == nullptr) {
        LOGE("Failed to get buffer array");
        return;
    }
    
    AudioEngine::addHiHat(bufferPtr, duration, levelScale, envelopeScale, hiHatHighpass);
    
    env->ReleaseDoubleArrayElements(buffer, bufferPtr, 0);
}

// ============================================================================
// Piano Synthesis JNI Functions
// ============================================================================

JNIEXPORT void JNICALL
Java_de_metaviewsoft_chordprogressionhelper_util_NativeAudio_generatePianoSample(
    JNIEnv* env, jobject /* this */, jdoubleArray buffer, jdouble frequency) {
    
    jsize length = env->GetArrayLength(buffer);
    jdouble* bufferPtr = env->GetDoubleArrayElements(buffer, nullptr);
    if (bufferPtr == nullptr) {
        LOGE("Failed to get buffer array");
        return;
    }
    
    AudioEngine::generatePianoSample(bufferPtr, length, frequency);
    
    env->ReleaseDoubleArrayElements(buffer, bufferPtr, 0);
}

// ============================================================================
// Karplus-Strong String JNI Functions
// ============================================================================

JNIEXPORT jlong JNICALL
Java_de_metaviewsoft_chordprogressionhelper_util_NativeAudio_createKarplusString(
    JNIEnv* /* env */, jobject /* this */, jdouble frequency, jint sampleRate,
    jint pluckStrength, jdouble decay) {
    
    auto* string = new KarplusStrongString(frequency, sampleRate, pluckStrength, decay);
    return reinterpret_cast<jlong>(string);
}

JNIEXPORT void JNICALL
Java_de_metaviewsoft_chordprogressionhelper_util_NativeAudio_destroyKarplusString(
    JNIEnv* /* env */, jobject /* this */, jlong handle) {
    
    auto* string = reinterpret_cast<KarplusStrongString*>(handle);
    delete string;
}

JNIEXPORT void JNICALL
Java_de_metaviewsoft_chordprogressionhelper_util_NativeAudio_pluckString(
    JNIEnv* /* env */, jobject /* this */, jlong handle) {
    
    auto* string = reinterpret_cast<KarplusStrongString*>(handle);
    string->pluck();
}

JNIEXPORT jdouble JNICALL
Java_de_metaviewsoft_chordprogressionhelper_util_NativeAudio_tickString(
    JNIEnv* /* env */, jobject /* this */, jlong handle) {
    
    auto* string = reinterpret_cast<KarplusStrongString*>(handle);
    return string->tick();
}

JNIEXPORT void JNICALL
Java_de_metaviewsoft_chordprogressionhelper_util_NativeAudio_tickStringBuffer(
    JNIEnv* env, jobject /* this */, jlong handle, jdoubleArray buffer, jint length) {
    
    auto* string = reinterpret_cast<KarplusStrongString*>(handle);
    jdouble* bufferPtr = env->GetDoubleArrayElements(buffer, nullptr);
    if (bufferPtr == nullptr) {
        LOGE("Failed to get buffer array");
        return;
    }
    
    for (int i = 0; i < length; ++i) {
        bufferPtr[i] += string->tick();
    }
    
    env->ReleaseDoubleArrayElements(buffer, bufferPtr, 0);
}

// ============================================================================
// Utility Functions
// ============================================================================

JNIEXPORT jdouble JNICALL
Java_de_metaviewsoft_chordprogressionhelper_util_NativeAudio_midiNoteToFrequency(
    JNIEnv* /* env */, jobject /* this */, jint midiNote) {
    
    return AudioEngine::midiNoteToFrequency(midiNote);
}

JNIEXPORT void JNICALL
Java_de_metaviewsoft_chordprogressionhelper_util_NativeAudio_doubleToPcmShort(
    JNIEnv* env, jobject /* this */, jdoubleArray input, jshortArray output) {
    
    jsize length = env->GetArrayLength(input);
    jdouble* inputPtr = env->GetDoubleArrayElements(input, nullptr);
    jshort* outputPtr = env->GetShortArrayElements(output, nullptr);
    
    if (inputPtr == nullptr || outputPtr == nullptr) {
        LOGE("Failed to get array pointers");
        if (inputPtr) env->ReleaseDoubleArrayElements(input, inputPtr, JNI_ABORT);
        if (outputPtr) env->ReleaseShortArrayElements(output, outputPtr, JNI_ABORT);
        return;
    }
    
    AudioEngine::doubleToPcmShort(inputPtr, reinterpret_cast<int16_t*>(outputPtr), length);
    
    env->ReleaseDoubleArrayElements(input, inputPtr, JNI_ABORT);
    env->ReleaseShortArrayElements(output, outputPtr, 0);
}

JNIEXPORT void JNICALL
Java_de_metaviewsoft_chordprogressionhelper_util_NativeAudio_applyOverdrive(
    JNIEnv* env, jobject /* this */, jdoubleArray buffer, jdouble gain) {
    
    jsize length = env->GetArrayLength(buffer);
    jdouble* bufferPtr = env->GetDoubleArrayElements(buffer, nullptr);
    if (bufferPtr == nullptr) {
        LOGE("Failed to get buffer array");
        return;
    }
    
    AudioEngine::applyOverdrive(bufferPtr, length, gain);
    
    env->ReleaseDoubleArrayElements(buffer, bufferPtr, 0);
}

// ============================================================================
// JNI_OnLoad
// ============================================================================

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* /* reserved */) {
    LOGI("NativeAudio library loaded");
    return JNI_VERSION_1_6;
}

} // extern "C"
