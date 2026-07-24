cmake_minimum_required(VERSION 3.22.1)

project("chordhelper")

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 16KB Page Size compatibility for Android 15+
# This ensures the native library works on devices with 16KB memory pages
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# Optimization flags for audio processing
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -ffast-math -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O0 -g")

# Add the native library
add_library(
    native-audio
    SHARED
    audio_engine.cpp
    native_audio_jni.cpp
)

# Find and link Android log library (for logging)
find_library(log-lib log)

# Link libraries
target_link_libraries(
    native-audio
    ${log-lib}
)

# Export ANativeActivity_onCreate for native activity support (if needed)
# Not required for JNI-only library, but doesn't hurt
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -u ANativeActivity_onCreate")
