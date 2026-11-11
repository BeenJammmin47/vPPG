//
// Created by rando on 11/8/2026.
//

#ifndef ECE420_VPPG_VPPG_DSP_H
#define ECE420_VPPG_VPPG_DSP_H

#include <vector>

// BPM calculation function
double calculateBPM(const std::vector<double>& signal,double fps);

// add new intensity sample to buffer
void addIntensitySample(double intensity);

// reset intensity buffer
void resetIntensityBuffer();

#endif //ECE420_VPPG_VPPG_DSP_H
