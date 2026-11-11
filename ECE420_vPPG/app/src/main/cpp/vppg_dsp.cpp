//
// Created by rando on 11/8/2026.
//

#include "vppg_dsp.h"
#include "kissfft/kiss_fft.h"

#include <vector>
#include <cmath>
#include <cstdlib>
#include <android/log.h>

constexpr double PI = 3.14159265358979323846;
constexpr int FPS = 30;                             // camera sits around 29.8-29.9 FPS
constexpr int BUFFER_SECONDS = 15;                  // buffer contains 15 seconds of data
constexpr int UPDATE_SECONDS = 5;                   // update the BPM estimate every 5 seconds
constexpr int BUFFER_SIZE = BUFFER_SECONDS * FPS;   // 450 size circBuffer
constexpr int UPDATE_SIZE = UPDATE_SECONDS * FPS;   // update every 150 elements

// current writeIndex for circBuffer
static int writeIndex = 0;
// amount of samples in the circBuffer, keeps track of circBuffer fill state
static int sampleCount = 0;
// amont of samples since the last update, update estimate every UPDATE_SIZE
static int samplesSinceUpdate = 0;
// flag to check whether we have calculated the first BPM yet
static bool firstEstimate = false;
// flag to check if we should trigger DSP
static bool trigDSP = false;

// circular buffer where average color intensities are filled
static std::vector<double> circBuffer(BUFFER_SIZE, 0);

double calculateBPM (const std::vector<double>& signal, double fps){
    int N = static_cast<int>(signal.size());

    if (N < 2 || fps <= 0.0){
        return 0.0;
    }

    // calculate mean (DC offset)
    double mean = 0.0;
    for (int i = 0; i < N; i++){
        mean += signal[i];
    }
    mean = mean / N;

    // DC removal + Hamming window
    std::vector<kiss_fft_cpx> input(N);
    std::vector<kiss_fft_cpx> output(N);

    for (int i = 0; i < N; i++){
        double window = 0.54 - 0.46 * cos(2.0 * PI * i / (N - 1));
        input[i].r = static_cast<kiss_fft_scalar>((signal[i] - mean) * window);
        input[i].i = 0;
    }

    // perform KISS FFT
    kiss_fft_cfg cfg = kiss_fft_alloc(N, 0, nullptr, nullptr);
    if (cfg == nullptr){
        return 0.0;
    }

    kiss_fft(cfg, input.data(), output.data());
    free(cfg);

    // find strongest peak
    double maxMagnitude = 0.0;
    int peakBin = 0;

    for (int k = 0; k <= N; k++){
        double frequency = k * fps / N;
        double bpm = frequency * 60.0;

        if (bpm < 40.0 || bpm > 210.0){
            continue;
        }

        double real = output[k].r;
        double imag = output[k].i;

        double magnitude = sqrt(real * real + imag * imag);

        if (magnitude > maxMagnitude){
            maxMagnitude = magnitude;
            peakBin = k;
        }
    }

    if (peakBin == 0){
        return 0.0;
    }

    // FFT Bin to BPM
    return 60.0 * peakBin * fps / N;
}

void addIntensitySample(double intensity){

    // write the current intensity onto the current index in the circular buffer
    circBuffer[writeIndex] = intensity;
    // set trigDSP to false. set it to true later if a DSP condition is met
    trigDSP = false;

    // increment writeIndex, resetting to 0 as needed
    if (writeIndex >= BUFFER_SIZE - 1)
        writeIndex = 0;
    else
        writeIndex++;

    // increment sampleCounter to keep track of circBuffer fill status
    if (sampleCount < BUFFER_SIZE)
        sampleCount++;
    // now check if circBuffer is actually full
    // make this a separate if statement to make sure 449 -> 450 edge case doesn't trigger
    if (sampleCount < BUFFER_SIZE)
        return;

    // check if circBuffer is full and first BPM has been calculated
    if (sampleCount == BUFFER_SIZE && firstEstimate == false){
        // set first BPM flag to true, set DSP calc flag to true, and reset sample update counter
        firstEstimate = true;
        trigDSP = true;
        samplesSinceUpdate = 0;
    }
    else {
        // otherwise, update samplesSinceUpdate
        samplesSinceUpdate++;

        // check if there is enough samples to update BPM and trigger DSP
        if (samplesSinceUpdate == UPDATE_SIZE) {
            trigDSP = true;
            samplesSinceUpdate = 0;
        }
    }

    // if trigger DSP is true, reorder the circular buffer into an easy-to-read vector
    if (trigDSP){

        // create an ordered buffer
        std::vector<double> orderedBuffer(BUFFER_SIZE, 0);

        // write onto the ordered buffer
        for (int i = 0; i < BUFFER_SIZE; i++){
            int circBufferIndex = (writeIndex + i) % BUFFER_SIZE;
            orderedBuffer[i] = circBuffer[circBufferIndex];
        }

        // now we have a filled buffer to feed into estimateBPM
        //calculateBPM(orderedBuffer, FPS);
    }
}