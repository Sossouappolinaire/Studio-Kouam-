package com.tvkouame.studio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Joue un tampon de samples mono 16 bits (44,1 kHz), en boucle ou une seule fois. */
public class PcmPlayer {

    public interface End { void onEnd(); }

    private final short[] data;
    private final int len;
    private final boolean loop;
    private AudioTrack track;
    private Thread th;
    private volatile boolean stop = false;
    private volatile boolean alive = false;
    private End end;

    public PcmPlayer(short[] data, int len, boolean loop) {
        this.data = data;
        this.len = len;
        this.loop = loop;
    }

    public void setOnEnd(End e) { end = e; }

    public boolean isPlaying() { return alive; }

    /** Position de lecture en samples depuis le début (en boucle : modulo la longueur). */
    public long positionFrames() {
        try {
            if (track == null || !alive) return 0;
            long p = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
            return loop && len > 0 ? p % len : p;
        } catch (Exception e) {
            return 0;
        }
    }

    public boolean start() {
        try {
            int min = AudioTrack.getMinBufferSize(Synth.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bs = Math.max(min, 16384);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(Synth.RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bs)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            track.play();
        } catch (Exception e) {
            track = null;
            return false;
        }
        alive = true;
        th = new Thread(this::run, "pcm-player");
        th.start();
        return true;
    }

    public void stop() {
        stop = true;
        try {
            if (track != null) { track.pause(); track.flush(); }
        } catch (Exception ignored) {}
    }

    private void run() {
        try {
            int pos = 0;
            long written = 0;
            while (!stop && len > 0) {
                if (pos >= len) {
                    if (loop) pos = 0; else break;
                }
                int n = Math.min(4096, len - pos);
                int w = track.write(data, pos, n);
                if (w < 0) break;
                pos += w;
                written += w;
            }
            if (!stop && !loop) {
                // laisse finir ce qui reste dans le tampon audio
                long limit = System.currentTimeMillis() + (long) (len * 1000L / Synth.RATE) + 3000;
                while (!stop && System.currentTimeMillis() < limit
                        && (track.getPlaybackHeadPosition() & 0xFFFFFFFFL) < written) {
                    try { Thread.sleep(25); } catch (InterruptedException ignored) {}
                }
            }
        } catch (Exception ignored) {
        } finally {
            alive = false;
            try { track.stop(); } catch (Exception ignored) {}
            try { track.release(); } catch (Exception ignored) {}
            if (end != null) end.onEnd();
        }
    }
}
