
#include <jni.h>
#include <vector>
#include <cmath>
#include "vppg_dsp.h"
#include <android/log.h>

#define LOG_TAG "vPPG_CPP"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

constexpr double PI = 3.14159265358979323846;

// Test native C++ function
extern "C"
JNIEXPORT jint JNICALL
Java_com_example_ece420_1vppg_MainActivity_testNative(
        JNIEnv* env,
        jobject thiz) {

    const double fps = 30.0;
    const int N = 600; // 20 second test

    std::vector<double> signal(N);

    //simulate 75 BPM heart rate
    double frequency = 90.0 / 60.0;

    for (int i = 0; i < N; i++){
        double t = i / fps;
        signal[i] = 125.0 + 10.0 * sin(2.0 * PI * frequency * t);
    }

    double bpm = calculateBPM(signal, fps);
    return static_cast<jint>(std::round(bpm));
}

// receive weighted RGB intensity
extern "C"
JNIEXPORT void JNICALL
Java_com_example_ece420_1vppg_MainActivity_addSample(
        JNIEnv* env,
        jobject thiz,
        jdouble intensity){

    addIntensitySample(intensity);
}
