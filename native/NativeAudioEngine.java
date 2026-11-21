package PACKAGE_PLACEHOLDER;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Process;

/** Salida de audio de baja latencia (AudioTrack en modo LOW_LATENCY, camino rapido del sistema). */
public class NativeAudioEngine {
  public static final int MAX_MULT = 16;
  public final NAMixer mixer;
  public final int sr;
  public final int burst;

  private volatile AudioTrack track;
  private Thread thread;
  private volatile boolean run = false;
  private volatile int mult = 2;
  private volatile boolean auto = true;
  private volatile long written = 0;
  private volatile int underruns = 0;
  private volatile boolean fast = false;
  private volatile String error = "";
  private final Object lifecycle = new Object();
  private final Object trackLock = new Object();

  public NativeAudioEngine(Context ctx) {
    int rate = 48000, b = 256;
    try {
      AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
      String r = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE);
      String f = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
      if (r != null) rate = Integer.parseInt(r.trim());
      if (f != null) b = Integer.parseInt(f.trim());
    } catch (Throwable t) { }
    if (rate < 8000 || rate > 192000) rate = 48000;
    if (b < 32 || b > 2048) b = 256;
    this.sr = rate;
    this.burst = b;
    this.mixer = new NAMixer(rate);
  }

  public void setMult(int m) {
    if (m < 1) m = 1;
    if (m > MAX_MULT) m = MAX_MULT;
    mult = m;
    AudioTrack t = track;
    if (t != null) { try { t.setBufferSizeInFrames(burst * m); } catch (Throwable e) { } }
  }

  public int getMult() { return mult; }
  public void setAuto(boolean a) { auto = a; }
  public boolean getAuto() { return auto; }
  public int getUnderruns() { return underruns; }
  public boolean isFast() { return fast; }
  public String getError() { return error; }
  public boolean isRunning() { return run; }

  public boolean start() {
    synchronized (lifecycle) {
      if (run) return true;
      if (Build.VERSION.SDK_INT < 26) { error = "Android menor a 8.0"; return false; }
      AudioTrack t = build();
      if (t == null) return false;
      track = t;
      run = true;
      thread = new Thread(new Runnable() { public void run() { loop(); } }, "NativeAudio");
      thread.start();
      return true;
    }
  }

  public void stop() {
    synchronized (lifecycle) {
      run = false;
      Thread th = thread;
      if (th != null) { try { th.join(1500); } catch (InterruptedException e) { } }
      thread = null;
      AudioTrack t;
      synchronized (trackLock) { t = track; track = null; }
      if (t != null) { try { t.stop(); } catch (Throwable e) { } try { t.release(); } catch (Throwable e) { } }
    }
  }

  private AudioTrack build() {
    try {
      AudioAttributes attr = new AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
          .build();
      AudioFormat fmt = new AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
          .setSampleRate(sr)
          .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
          .build();
      AudioTrack t = new AudioTrack.Builder()
          .setAudioAttributes(attr)
          .setAudioFormat(fmt)
          .setTransferMode(AudioTrack.MODE_STREAM)
          .setBufferSizeInBytes(burst * MAX_MULT * 2 * 4)
          .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
          .build();
      if (t.getState() != AudioTrack.STATE_INITIALIZED) { error = "AudioTrack no inicio"; t.release(); return null; }
      t.setBufferSizeInFrames(burst * mult);
      error = "";
      return t;
    } catch (Throwable e) {
      error = String.valueOf(e);
      return null;
    }
  }

  private void loop() {
    try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Throwable e) { }
    float[] buf = new float[burst * 2];
    while (run) {
      AudioTrack t = track;
      if (t == null) break;
      try { t.play(); } catch (Throwable e) { error = String.valueOf(e); }
      long startNs = System.nanoTime();
      long lastCheck = startNs;
      int lastUnder = -1;
      boolean dead = false;
      while (run) {
        mixer.render(buf, burst);
        int w = t.write(buf, 0, buf.length, AudioTrack.WRITE_BLOCKING);
        if (w < 0) { dead = true; error = "write " + w; break; }
        written += burst;
        long now = System.nanoTime();
        if (now - lastCheck > 700000000L) {
          lastCheck = now;
          int u = 0;
          try { u = t.getUnderrunCount(); } catch (Throwable e) { }
          underruns = u;
          if (now - startNs > 2000000000L) {
            if (lastUnder < 0) lastUnder = u;
            else if (u > lastUnder) {
              lastUnder = u;
              if (auto && mult < MAX_MULT) { mult++; try { t.setBufferSizeInFrames(burst * mult); } catch (Throwable e) { } }
            }
          }
        }
      }
      if (run && dead) {
        // el sistema cerro la salida (por ejemplo al conectar audifonos): se vuelve a abrir
        synchronized (trackLock) {
          try { t.stop(); } catch (Throwable e) { }
          try { t.release(); } catch (Throwable e) { }
          AudioTrack n = null;
          for (int i = 0; i < 20 && run && n == null; i++) {
            n = build();
            if (n == null) { try { Thread.sleep(250); } catch (InterruptedException e) { } }
          }
          track = n;
          written = 0;
          if (n == null) { run = false; break; }
        }
      }
    }
  }

  /** Latencia estimada en ms: tiempo entre escribir una muestra y que salga por la bocina. */
  public double latencyMs() {
    AudioTrack t = track;
    if (t == null) return -1;
    try {
      AudioTimestamp ts = new AudioTimestamp();
      if (t.getTimestamp(ts)) {
        double presented = ts.framePosition + (System.nanoTime() - ts.nanoTime) * (double) sr / 1e9;
        double lat = (written - presented) * 1000.0 / sr;
        if (lat > 0 && lat < 2000) return lat;
      }
    } catch (Throwable e) { }
    return mult * burst * 1000.0 / sr;
  }
}
