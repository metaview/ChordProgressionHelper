#ifndef AUDIO_ENGINE_H
#define AUDIO_ENGINE_H

#include <cstdint>
#include <vector>
#include <cmath>
#include <random>

namespace chordhelper {

/**
 * Karplus-Strong String Synthesis
 * Simulates a plucked string using a delay line with feedback
 */
class KarplusStrongString {
public:
    KarplusStrongString(double frequency, int sampleRate, int pluckStrength, double decay = 0.998);
    void pluck();
    double tick();
    
private:
    int pluckStrengthLevel;
    int bufferSize;
    std::vector<double> ringBuffer;
    int currentIndex;
    double decayFactor;
    std::mt19937 rng;
    std::uniform_real_distribution<double> dist;
};

/**
 * Audio synthesis functions
 */
class AudioEngine {
public:
    static constexpr int SAMPLE_RATE = 44100;
    
    // Drum synthesis
    static void addKick(double* buffer, int duration, double levelScale = 1.0, 
                       double envelopeScale = 1.0, double drumLevel = 1.0);
    static void addSnare(double* buffer, int duration, double levelScale = 1.0,
                        double envelopeScale = 1.0, double drumLevel = 1.0);
    static void addHiHat(double* buffer, int duration, double levelScale = 1.0,
                        double envelopeScale = 1.0, double hiHatHighpass = 1.0);
    
    // Piano synthesis using additive synthesis
    static void generatePianoSample(double* buffer, int numSamples, double frequency);
    
    // Utility functions
    static double midiNoteToFrequency(int midiNote);
    static void doubleToPcmShort(const double* input, int16_t* output, int length);
    
    // Effects
    static void applyOverdrive(double* buffer, int length, double gain);
    static void applyLowpass(double* buffer, int length, double alpha, double& prevLP);
    
private:
    static constexpr double PI = 3.14159265358979323846;
};

} // namespace chordhelper

#endif // AUDIO_ENGINE_H
