#ifndef CHORDHELPER_AUDIO_BRIDGE_H
#define CHORDHELPER_AUDIO_BRIDGE_H

/*
 * Plain C façade over the portable C++ DSP engine in app/src/main/cpp/audio_engine.{h,cpp}
 * (Android's JNI layer wraps the same engine — see app/src/main/cpp/native_audio_jni.cpp for the
 * JNI-flavored equivalent of every function below).
 *
 * Kotlin/Native's cinterop tool only understands a C (or Objective-C) API, not C++ directly, so
 * this header is the seam iosMain's NativeAudioBridge actual binds against — see
 * shared/src/iosMain/kotlin/.../util/CinteropNativeAudioBridge.kt. Karplus-Strong string handles
 * are plain pointers reinterpreted as integers, exactly like the JNI side's `jlong`, so Kotlin's
 * `Long`-typed handles need no extra pointer wrapping/unwrapping.
 */

#ifdef __cplusplus
extern "C" {
#endif

int chordhelper_audio_is_available(void);

void chordhelper_audio_add_kick(double *buffer, int duration, double levelScale, double envelopeScale, double drumLevel);
void chordhelper_audio_add_snare(double *buffer, int duration, double levelScale, double envelopeScale, double drumLevel);
void chordhelper_audio_add_hihat(double *buffer, int duration, double levelScale, double envelopeScale, double hiHatHighpass);

void chordhelper_audio_generate_piano_sample(double *buffer, int numSamples, double frequency);

long long chordhelper_audio_create_karplus_string(double frequency, int sampleRate, int pluckStrength, double decay);
void chordhelper_audio_destroy_karplus_string(long long handle);
void chordhelper_audio_pluck_string(long long handle);
double chordhelper_audio_tick_string(long long handle);
void chordhelper_audio_tick_string_buffer(long long handle, double *buffer, int length);

double chordhelper_audio_midi_note_to_frequency(int midiNote);
void chordhelper_audio_double_to_pcm_short(const double *input, short *output, int length);
void chordhelper_audio_apply_overdrive(double *buffer, int length, double gain);

#ifdef __cplusplus
}
#endif

#endif /* CHORDHELPER_AUDIO_BRIDGE_H */
