package com.erezbabayan.babitrans;

import android.content.Context; import android.media.*; import android.net.Uri;
import java.nio.*;

/** Decodes any Android-supported audio (WhatsApp .ogg/opus, m4a, mp3, wav...) to 16 kHz mono float PCM. */
final class AudioDecoder {
    static float[] decode(Context ctx, Uri uri) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        ex.setDataSource(ctx, uri, null);
        int track = -1; MediaFormat fmt = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat f = ex.getTrackFormat(i); String m = f.getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith("audio/")) { track = i; fmt = f; break; }
        }
        if (track < 0) throw new Exception("לא נמצא ערוץ שמע בקובץ");
        ex.selectTrack(track);
        MediaCodec codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
        codec.configure(fmt, null, null, 0); codec.start();
        int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        FloatArr mono = new FloatArr(); MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean inEos = false, outEos = false; boolean isFloat = false;
        while (!outEos) {
            if (!inEos) {
                int ii = codec.dequeueInputBuffer(10000);
                if (ii >= 0) {
                    ByteBuffer b = codec.getInputBuffer(ii); int n = ex.readSampleData(b, 0);
                    if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEos = true; }
                    else { codec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                }
            }
            int oi = codec.dequeueOutputBuffer(info, 10000);
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat of = codec.getOutputFormat();
                rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT;
            } else if (oi >= 0) {
                ByteBuffer ob = codec.getOutputBuffer(oi); ob.position(info.offset); ob.limit(info.offset + info.size);
                ob.order(ByteOrder.nativeOrder());
                if (isFloat) { FloatBuffer fb = ob.asFloatBuffer(); int frames = fb.remaining() / ch;
                    for (int f = 0; f < frames; f++) { float s = 0; for (int c = 0; c < ch; c++) s += fb.get(); mono.add(s / ch); } }
                else { ShortBuffer sb = ob.asShortBuffer(); int frames = sb.remaining() / ch;
                    for (int f = 0; f < frames; f++) { float s = 0; for (int c = 0; c < ch; c++) s += sb.get() / 32768f; mono.add(s / ch); } }
                codec.releaseOutputBuffer(oi, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
            }
        }
        codec.stop(); codec.release(); ex.release();
        return resample(mono.toArray(), rate, 16000);
    }
    static float[] resample(float[] in, int from, int to) {
        if (from == to || in.length == 0) return in;
        int n = (int) ((long) in.length * to / from); float[] out = new float[n]; double r = (double) from / to;
        if (from > to) { // box-filter average then sample (simple anti-alias)
            for (int i = 0; i < n; i++) { int a = (int) (i * r), b = Math.min(in.length, (int) ((i + 1) * r)); float s = 0;
                for (int k = a; k < b; k++) s += in[k]; out[i] = b > a ? s / (b - a) : in[Math.min(a, in.length - 1)]; }
        } else for (int i = 0; i < n; i++) { double p = i * r; int a = (int) p; double t = p - a;
            float x = in[a], y = in[Math.min(a + 1, in.length - 1)]; out[i] = (float) (x + (y - x) * t); }
        return out;
    }
    static final class FloatArr { float[] a = new float[1 << 16]; int n;
        void add(float v) { if (n == a.length) a = java.util.Arrays.copyOf(a, a.length * 2); a[n++] = v; }
        float[] toArray() { return java.util.Arrays.copyOf(a, n); } }
}
