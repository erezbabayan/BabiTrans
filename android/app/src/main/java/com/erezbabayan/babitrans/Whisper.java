package com.erezbabayan.babitrans;

/** Thin JNI wrapper around whisper.cpp (on-device, CPU). */
public final class Whisper {
    static { System.loadLibrary("babiwhisper"); }
    static native long init(String modelPath);
    static native void free(long ctx);
    static native String transcribe(long ctx, float[] pcm16k, String lang, int nThreads, int jobId, int timeoutSec);
    static native void cancel(int jobId);
    /** (phase<<24)|(tokens<<8)|pct, or -1 when the job isn't inside whisper_full. */
    static native int progress(int jobId);
}
