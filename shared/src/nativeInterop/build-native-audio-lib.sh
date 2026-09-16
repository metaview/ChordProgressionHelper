#!/bin/bash
# Compiles the portable C++ audio engine (app/src/main/cpp/audio_engine.{h,cpp}) plus its plain-C
# cinterop façade (chordhelper_audio_bridge.cpp) into a static library for one Kotlin/Native iOS
# target. Invoked by shared/build.gradle (one task per iOS target) rather than done inline in the
# build script, so it can be run and debugged directly from a terminal too:
#
#   ./build-native-audio-lib.sh <clang-target-triple> <sdk> <out-dir>
#   ./build-native-audio-lib.sh arm64-apple-ios15.0-simulator iphonesimulator /tmp/out
#
# Exits non-zero (with clang/ar's own error output) on any failure — Gradle surfaces that as the
# task's failure, no separate error handling needed here.
set -euo pipefail

CLANG_TARGET="$1"
SDK="$2"
OUT_DIR="$3"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AUDIO_ENGINE_DIR="$SCRIPT_DIR/../../../app/src/main/cpp"
BRIDGE_CPP="$SCRIPT_DIR/cpp/chordhelper_audio_bridge.cpp"
BRIDGE_INCLUDE_DIR="$SCRIPT_DIR/cinterop/include"

SDK_PATH="$(xcrun --sdk "$SDK" --show-sdk-path)"

OBJ_DIR="$OUT_DIR/obj"
mkdir -p "$OBJ_DIR"

CXXFLAGS=(-target "$CLANG_TARGET" -isysroot "$SDK_PATH" -std=c++17 -O3 -ffast-math -fPIC)

xcrun clang++ "${CXXFLAGS[@]}" -c "$AUDIO_ENGINE_DIR/audio_engine.cpp" -o "$OBJ_DIR/audio_engine.o"
xcrun clang++ "${CXXFLAGS[@]}" -I"$AUDIO_ENGINE_DIR" -I"$BRIDGE_INCLUDE_DIR" -c "$BRIDGE_CPP" -o "$OBJ_DIR/chordhelper_audio_bridge.o"

rm -f "$OUT_DIR/libchordhelperaudio.a"
xcrun ar rcs "$OUT_DIR/libchordhelperaudio.a" "$OBJ_DIR/audio_engine.o" "$OBJ_DIR/chordhelper_audio_bridge.o"
