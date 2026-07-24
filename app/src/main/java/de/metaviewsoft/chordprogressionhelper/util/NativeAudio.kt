package de.metaviewsoft.chordprogressionhelper.util

import android.util.Log

/**
 * JNI wrapper for native audio synthesis functions
 * 
 * This class provides Kotlin bindings to the native C++ audio engine
 * for performance-critical audio synthesis operations.
 */
object NativeAudio {
    
    private const val TAG = "NativeAudio"
    
    // Flag to track if native library was loaded successfully
    @Volatile
    private var isNativeLibraryLoaded = false
    
    init {
        try {
            System.loadLibrary("native-audio")
            isNativeLibraryLoaded = true
            Log.i(TAG, "Native audio library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native audio library: ${e.message}", e)
            isNativeLibraryLoaded = false
        }
    }
    
    /**
     * Check if native library is available
     */
    fun isAvailable(): Boolean = isNativeLibraryLoaded
    
    // ========================================================================
    // Drum Synthesis
    // ========================================================================
    
    /**
     * Add kick drum to buffer
     * @param buffer Double array buffer to add kick to (modified in-place)
     * @param duration Number of samples to generate
     * @param levelScale Volume multiplier for this specific call
     * @param envelopeScale Global envelope scale factor
     * @param drumLevel Global drum level multiplier
     */
    external fun addKick(
        buffer: DoubleArray,
        duration: Int,
        levelScale: Double = 1.0,
        envelopeScale: Double = 1.0,
        drumLevel: Double = 1.0
    )
    
    /**
     * Add snare drum to buffer
     */
    external fun addSnare(
        buffer: DoubleArray,
        duration: Int,
        levelScale: Double = 1.0,
        envelopeScale: Double = 1.0,
        drumLevel: Double = 1.0
    )
    
    /**
     * Add hi-hat to buffer
     */
    external fun addHiHat(
        buffer: DoubleArray,
        duration: Int,
        levelScale: Double = 1.0,
        envelopeScale: Double = 1.0,
        hiHatHighpass: Double = 1.0
    )
    
    // ========================================================================
    // Piano Synthesis
    // ========================================================================
    
    /**
     * Generate piano sample using additive synthesis
     * @param buffer Double array to fill with piano sample
     * @param frequency Frequency in Hz
     */
    external fun generatePianoSample(
        buffer: DoubleArray,
        frequency: Double
    )
    
    // ========================================================================
    // Karplus-Strong String Synthesis
    // ========================================================================
    
    /**
     * Create a Karplus-Strong string (returns native handle)
     * @return Native pointer handle (must be destroyed with destroyKarplusString)
     */
    external fun createKarplusString(
        frequency: Double,
        sampleRate: Int,
        pluckStrength: Int,
        decay: Double = 0.998
    ): Long
    
    /**
     * Destroy a Karplus-Strong string
     * @param handle Native pointer from createKarplusString
     */
    external fun destroyKarplusString(handle: Long)
    
    /**
     * Pluck the string (initialize with noise)
     */
    external fun pluckString(handle: Long)
    
    /**
     * Get next sample from string
     */
    external fun tickString(handle: Long): Double
    
    /**
     * Fill buffer with samples from string (adds to existing buffer content)
     * @param handle Native pointer from createKarplusString
     * @param buffer Buffer to add samples to
     * @param length Number of samples to generate
     */
    external fun tickStringBuffer(handle: Long, buffer: DoubleArray, length: Int)
    
    // ========================================================================
    // Utility Functions
    // ========================================================================
    
    /**
     * Convert MIDI note number to frequency in Hz
     */
    external fun midiNoteToFrequency(midiNote: Int): Double
    
    /**
     * Convert double array to PCM short array
     * @param input Double array with values in range [-1.0, 1.0]
     * @param output Short array to fill with PCM values
     */
    external fun doubleToPcmShort(input: DoubleArray, output: ShortArray)
    
    /**
     * Apply overdrive/distortion effect to buffer
     * @param buffer Buffer to process (modified in-place)
     * @param gain Gain factor for overdrive intensity
     */
    external fun applyOverdrive(buffer: DoubleArray, gain: Double)
    
    // ========================================================================
    // Kotlin wrapper class for Karplus-Strong strings with auto-cleanup
    // ========================================================================
    
    /**
     * Kotlin wrapper for native Karplus-Strong string with automatic resource management
     */
    class KarplusString(
        frequency: Double,
        sampleRate: Int,
        pluckStrength: Int,
        decay: Double = 0.998
    ) : AutoCloseable {
        
        private var nativeHandle: Long = 0L
        private var isClosed = false
        
        init {
            if (!isNativeLibraryLoaded) {
                throw IllegalStateException("Native library not loaded")
            }
            nativeHandle = createKarplusString(frequency, sampleRate, pluckStrength, decay)
        }
        
        fun pluck() {
            checkNotClosed()
            pluckString(nativeHandle)
        }
        
        fun tick(): Double {
            checkNotClosed()
            return tickString(nativeHandle)
        }
        
        fun tickBuffer(buffer: DoubleArray, length: Int) {
            checkNotClosed()
            tickStringBuffer(nativeHandle, buffer, length)
        }
        
        override fun close() {
            if (!isClosed && nativeHandle != 0L) {
                destroyKarplusString(nativeHandle)
                nativeHandle = 0L
                isClosed = true
            }
        }
        
        private fun checkNotClosed() {
            if (isClosed) {
                throw IllegalStateException("KarplusString has been closed")
            }
        }
        
        @Suppress("unused")
        protected fun finalize() {
            if (!isClosed) {
                Log.w(TAG, "KarplusString was not properly closed, finalizing...")
                close()
            }
        }
    }
}
