#include "chordhelper_audio_bridge.h"
#include "audio_engine.h"

using namespace chordhelper;

int chordhelper_audio_is_available(void) {
    return 1;
}

void chordhelper_audio_add_kick(double *buffer, int duration, double levelScale, double envelopeScale, double drumLevel) {
    AudioEngine::addKick(buffer, duration, levelScale, envelopeScale, drumLevel);
}

void chordhelper_audio_add_snare(double *buffer, int duration, double levelScale, double envelopeScale, double drumLevel) {
    AudioEngine::addSnare(buffer, duration, levelScale, envelopeScale, drumLevel);
}

void chordhelper_audio_add_hihat(double *buffer, int duration, double levelScale, double envelopeScale, double hiHatHighpass) {
    AudioEngine::addHiHat(buffer, duration, levelScale, envelopeScale, hiHatHighpass);
}

void chordhelper_audio_generate_piano_sample(double *buffer, int numSamples, double frequency) {
    AudioEngine::generatePianoSample(buffer, numSamples, frequency);
}

long long chordhelper_audio_create_karplus_string(double frequency, int sampleRate, int pluckStrength, double decay) {
    auto *string = new KarplusStrongString(frequency, sampleRate, pluckStrength, decay);
    return reinterpret_cast<long long>(string);
}

void chordhelper_audio_destroy_karplus_string(long long handle) {
    delete reinterpret_cast<KarplusStrongString *>(handle);
}

void chordhelper_audio_pluck_string(long long handle) {
    reinterpret_cast<KarplusStrongString *>(handle)->pluck();
}

double chordhelper_audio_tick_string(long long handle) {
    return reinterpret_cast<KarplusStrongString *>(handle)->tick();
}

void chordhelper_audio_tick_string_buffer(long long handle, double *buffer, int length) {
    auto *string = reinterpret_cast<KarplusStrongString *>(handle);
    for (int i = 0; i < length; ++i) {
        buffer[i] += string->tick();
    }
}

double chordhelper_audio_midi_note_to_frequency(int midiNote) {
    return AudioEngine::midiNoteToFrequency(midiNote);
}

void chordhelper_audio_double_to_pcm_short(const double *input, short *output, int length) {
    static_assert(sizeof(short) == sizeof(int16_t), "short must be 16 bits on this target");
    AudioEngine::doubleToPcmShort(input, reinterpret_cast<int16_t *>(output), length);
}

void chordhelper_audio_apply_overdrive(double *buffer, int length, double gain) {
    AudioEngine::applyOverdrive(buffer, length, gain);
}
