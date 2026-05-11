package com.transkription;

import android.content.Intent;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;

public class AudioConverter extends ReactContextBaseJavaModule {

    AudioConverter(ReactApplicationContext context) {
        super(context);
    }

    @Override
    public String getName() {
        return "AudioConverter";
    }

    @ReactMethod
    public void startTranscriptionService(Promise promise) {
        try {
            Intent intent = new Intent(getReactApplicationContext(), TranscriptionService.class);
            getReactApplicationContext().startForegroundService(intent);
            promise.resolve(null);
        } catch (Exception e) {
            promise.reject("SERVICE_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void stopTranscriptionService(Promise promise) {
        try {
            Intent intent = new Intent(getReactApplicationContext(), TranscriptionService.class);
            getReactApplicationContext().stopService(intent);
            promise.resolve(null);
        } catch (Exception e) {
            promise.reject("SERVICE_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void convertToWav(String inputPath, String outputPath, Promise promise) {
        new Thread(() -> {
            try {
                doConvert(inputPath, outputPath, promise);
            } catch (Exception e) {
                promise.reject("CONVERSION_ERROR", e.getMessage() != null ? e.getMessage() : "Unknown error");
            }
        }).start();
    }

    private void doConvert(String inputPath, String outputPath, Promise promise) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(inputPath);

        int audioTrack = -1;
        MediaFormat inputFormat = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat fmt = extractor.getTrackFormat(i);
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                audioTrack = i;
                inputFormat = fmt;
                break;
            }
        }
        if (audioTrack < 0) {
            extractor.release();
            promise.reject("NO_AUDIO", "No audio track found");
            return;
        }
        extractor.selectTrack(audioTrack);

        String mime = inputFormat.getString(MediaFormat.KEY_MIME);
        int srcSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int srcChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

        MediaCodec decoder = MediaCodec.createDecoderByType(mime);
        decoder.configure(inputFormat, null, null, 0);
        decoder.start();

        ArrayList<byte[]> pcmChunks = new ArrayList<>();
        long totalPcmBytes = 0;
        boolean inputDone = false;
        boolean outputDone = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;

        while (!outputDone) {
            if (!inputDone) {
                int idx = decoder.dequeueInputBuffer(10_000);
                if (idx >= 0) {
                    ByteBuffer buf = decoder.getInputBuffer(idx);
                    int n = extractor.readSampleData(buf, 0);
                    if (n < 0) {
                        decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        decoder.queueInputBuffer(idx, 0, n, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }
            int idx = decoder.dequeueOutputBuffer(info, 10_000);
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat outFmt = decoder.getOutputFormat();
                if (outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    pcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING);
                }
            } else if (idx >= 0) {
                ByteBuffer outBuf = decoder.getOutputBuffer(idx);
                outBuf.position(info.offset);
                outBuf.limit(info.offset + info.size);
                byte[] chunk = new byte[info.size];
                outBuf.get(chunk);
                pcmChunks.add(chunk);
                totalPcmBytes += info.size;
                decoder.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            }
        }

        decoder.stop();
        decoder.release();
        extractor.release();

        byte[] rawPcm = mergeChunks(pcmChunks, totalPcmBytes);
        byte[] pcm16mono = toMono16kHz(rawPcm, pcmEncoding, srcChannels, srcSampleRate);
        writeWav(outputPath, pcm16mono, 16000, 1);

        promise.resolve(outputPath);
    }

    private byte[] mergeChunks(ArrayList<byte[]> chunks, long total) {
        byte[] out = new byte[(int) total];
        int pos = 0;
        for (byte[] c : chunks) {
            System.arraycopy(c, 0, out, pos, c.length);
            pos += c.length;
        }
        return out;
    }

    private byte[] toMono16kHz(byte[] input, int encoding, int channels, int srcRate) {
        float[] mono;
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            int frames = input.length / 4 / channels;
            mono = new float[frames];
            ByteBuffer buf = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < frames; i++) {
                float sum = 0;
                for (int c = 0; c < channels; c++) sum += buf.getFloat();
                mono[i] = sum / channels;
            }
        } else {
            int frames = input.length / 2 / channels;
            mono = new float[frames];
            ByteBuffer buf = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < frames; i++) {
                float sum = 0;
                for (int c = 0; c < channels; c++) sum += buf.getShort() / 32768f;
                mono[i] = sum / channels;
            }
        }

        int outFrames = (int) ((long) mono.length * 16000L / srcRate);
        byte[] out = new byte[outFrames * 2];
        ByteBuffer outBuf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < outFrames; i++) {
            float pos = (float) i * srcRate / 16000f;
            int lo = (int) pos;
            float frac = pos - lo;
            float s = (lo + 1 < mono.length)
                ? mono[lo] * (1 - frac) + mono[lo + 1] * frac
                : mono[Math.min(lo, mono.length - 1)];
            outBuf.putShort((short) Math.max(-32768, Math.min(32767, (int) (s * 32767))));
        }
        return out;
    }

    private void writeWav(String path, byte[] pcm, int sampleRate, int channels) throws IOException {
        int bitsPerSample = 16;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        FileOutputStream out = new FileOutputStream(path);
        ByteBuffer hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        hdr.put(new byte[]{'R', 'I', 'F', 'F'});
        hdr.putInt(pcm.length + 36);
        hdr.put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
        hdr.putInt(16);
        hdr.putShort((short) 1);
        hdr.putShort((short) channels);
        hdr.putInt(sampleRate);
        hdr.putInt(byteRate);
        hdr.putShort((short) (channels * bitsPerSample / 8));
        hdr.putShort((short) bitsPerSample);
        hdr.put(new byte[]{'d', 'a', 't', 'a'});
        hdr.putInt(pcm.length);
        out.write(hdr.array());
        out.write(pcm);
        out.close();
    }
}
