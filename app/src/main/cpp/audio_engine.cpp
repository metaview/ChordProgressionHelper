#include "audio_engine.h"
#include <algorithm>
#include <cstring>

namespace chordhelper {

// ============================================================================
// KarplusStrongString Implementation
// ============================================================================

KarplusStrongString::KarplusStrongString(double frequency, int sampleRate, 
                                         int pluckStrength, double decay)
    : pluckStrengthLevel(pluckStrength)
    , bufferSize(std::max(1, static_cast<int>(sampleRate / frequency)))
    , ringBuffer(bufferSize, 0.0)
    , currentIndex(0)
    , decayFactor(decay)
    , rng(std::random_device{}())
    , dist(-1.0, 1.0)
{
}

void KarplusStrongString::pluck() {
    std::vector<double> whiteNoise(bufferSize);
    for (int i = 0; i < bufferSize; ++i) {
        whiteNoise[i] = dist(rng);
    }
    
    switch (pluckStrengthLevel) {
        case 1:
            // Hard pluck - full white noise
            std::copy(whiteNoise.begin(), whiteNoise.end(), ringBuffer.begin());
            break;
            
        case 3:
            // Soft pluck - averaged noise for rounder attack
            for (int i = 0; i < bufferSize; ++i) {
                double sum = 0.0;
                for (int j = 0; j < 4; ++j) {
                    int idx = i - j;
                    sum += (idx >= 0) ? whiteNoise[idx] : 0.0;
                }
                ringBuffer[i] = sum / 4.0;
            }
            break;
            
        default:
            // Medium pluck
            ringBuffer[0] = whiteNoise[0];
            for (int i = 1; i < bufferSize; ++i) {
                ringBuffer[i] = (whiteNoise[i] + whiteNoise[i - 1]) / 2.0;
            }
            break;
    }
}

double KarplusStrongString::tick() {
    double currentSample = ringBuffer[currentIndex];
    int nextIdx = (currentIndex + 1) % bufferSize;
    double nextSample = (currentSample + ringBuffer[nextIdx]) * 0.5 * decayFactor;
    ringBuffer[currentIndex] = nextSample;
    currentIndex = nextIdx;
    return currentSample;
}

// ============================================================================
// AudioEngine Implementation
// ============================================================================

void AudioEngine::addKick(double* buffer, int duration, double levelScale,
                         double envelopeScale, double drumLevel) {
    const double freq = 60.0;
    const int kickDuration = std::min(static_cast<int>(duration * 0.5), duration);
    
    for (int i = 0; i < kickDuration; ++i) {
        const double progress = static_cast<double>(i) / kickDuration;
        const double envelope = std::pow(1.0 - progress, 4.0) * envelopeScale;
        const double angle = 2.0 * PI * i * (freq * (1.0 - progress * 0.5)) / SAMPLE_RATE;
        buffer[i] += std::sin(angle) * envelope * 3.6 * drumLevel * levelScale;
    }
}

void AudioEngine::addSnare(double* buffer, int duration, double levelScale,
                          double envelopeScale, double drumLevel) {
    const int snareDuration = std::min(static_cast<int>(duration * 0.2), duration);
    std::mt19937 rng(std::random_device{}());
    std::uniform_real_distribution<double> dist(-1.0, 1.0);
    
    for (int i = 0; i < snareDuration; ++i) {
        const double noise = dist(rng);
        const double envelope = std::pow(1.0 - static_cast<double>(i) / snareDuration, 2.0) * envelopeScale;
        buffer[i] += noise * envelope * 1.4 * drumLevel * levelScale;
    }
}

void AudioEngine::addHiHat(double* buffer, int duration, double levelScale,
                          double envelopeScale, double hiHatHighpass) {
    const int hiHatDuration = std::min(static_cast<int>(duration * 0.25), duration);
    std::mt19937 rng(std::random_device{}());
    std::uniform_real_distribution<double> dist(-1.0, 1.0);
    
    double lastNoise = 0.0;
    for (int i = 0; i < hiHatDuration; ++i) {
        const double white = dist(rng);
        const double highPass = (white - lastNoise) * hiHatHighpass;
        lastNoise = white;
        const double envelope = std::pow(1.0 - static_cast<double>(i) / hiHatDuration, 2.0) * envelopeScale;
        buffer[i] += highPass * envelope * 1.2 * levelScale;
    }
}

void AudioEngine::generatePianoSample(double* buffer, int numSamples, double frequency) {
    // Piano harmonics with specific amplitude ratios
    const std::vector<std::pair<double, double>> harmonics = {
        {1.0, 1.0},   // Fundamental
        {2.0, 0.6},   // 2nd harmonic (octave)
        {3.0, 0.3}    // 3rd harmonic
    };
    
    // ADSR envelope parameters
    const double attackTime = 0.002;  // 2ms attack
    const double decayTime = 0.15;    // 150ms decay
    const double sustainLevel = 0.3;  // 30% sustain
    
    const int attackSamples = static_cast<int>(attackTime * SAMPLE_RATE);
    const int decaySamples = static_cast<int>(decayTime * SAMPLE_RATE);
    const int attackDecaySamples = attackSamples + decaySamples;
    
    std::memset(buffer, 0, numSamples * sizeof(double));
    
    for (int i = 0; i < numSamples; ++i) {
        const double t = static_cast<double>(i) / SAMPLE_RATE;
        double sample = 0.0;
        
        // Add each harmonic
        for (const auto& [harmonic, amplitude] : harmonics) {
            const double freq = frequency * harmonic;
            sample += amplitude * std::sin(2.0 * PI * freq * t);
        }
        
        // ADSR envelope
        double envelope;
        if (i < attackSamples) {
            envelope = static_cast<double>(i) / attackSamples;
        } else if (i < attackDecaySamples) {
            const double decayProgress = static_cast<double>(i - attackSamples) / decaySamples;
            envelope = 1.0 - (1.0 - sustainLevel) * decayProgress;
        } else {
            const double releaseProgress = static_cast<double>(i - attackDecaySamples) / 
                                          (numSamples - attackDecaySamples);
            envelope = sustainLevel * (1.0 - releaseProgress);
        }
        envelope = std::clamp(envelope, 0.0, 1.0);
        
        buffer[i] = sample * envelope;
    }
    
    // Normalize
    double maxVal = 0.0;
    for (int i = 0; i < numSamples; ++i) {
        maxVal = std::max(maxVal, std::abs(buffer[i]));
    }
    if (maxVal > 0.0) {
        for (int i = 0; i < numSamples; ++i) {
            buffer[i] /= maxVal;
        }
    }
}

double AudioEngine::midiNoteToFrequency(int midiNote) {
    int midi = midiNote;
    // Chords are stored as offsets around C=0 (range about -20..+13) and must be
    // lifted into the C4 register. Real MIDI notes from the solo keyboard start at
    // B0 = 23, so the threshold must sit between those two ranges - otherwise low
    // solo notes (octave 1-2) would be wrongly shifted up an extra +60.
    if (midi < 18) {
        midi += 60;  // Move offset-encoded notes into mid register (C4 = 60)
    }
    return 440.0 * std::pow(2.0, (midi - 69) / 12.0);
}

void AudioEngine::doubleToPcmShort(const double* input, int16_t* output, int length) {
    for (int i = 0; i < length; ++i) {
        int32_t pcmValue = static_cast<int32_t>(input[i] * 32767.0);
        pcmValue = std::clamp(pcmValue, -32768, 32767);
        output[i] = static_cast<int16_t>(pcmValue);
    }
}

void AudioEngine::applyOverdrive(double* buffer, int length, double gain) {
    for (int i = 0; i < length; ++i) {
        buffer[i] = std::tanh(buffer[i] * gain);
    }
}

void AudioEngine::applyLowpass(double* buffer, int length, double alpha, double& prevLP) {
    for (int i = 0; i < length; ++i) {
        prevLP = prevLP + alpha * (buffer[i] - prevLP);
        buffer[i] = prevLP;
    }
}

} // namespace chordhelper
