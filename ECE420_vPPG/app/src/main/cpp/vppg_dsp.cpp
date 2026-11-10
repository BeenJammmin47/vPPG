//
// Created by rando on 11/8/2026.
//

#include "vppg_dsp.h"
#include "kissfft/kiss_fft.h"

#include <vector>
#include <cmath>
#include <cstdlib>

constexpr double PI = 3.14159265358979323846;

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