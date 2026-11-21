package PACKAGE_PLACEHOLDER;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Mezclador de samples (solo Java, sin Android). Reproduce pads con EQ de 3 bandas,
 *  reverb tipo delay con realimentacion, corte por release y clics de metronomo. */
public class NAMixer {
  public static final int MAXV = 64;
  public static final int MAXC = 16;
  public static final int MAXBLOCK = 4096;

  public final int sr;
  private final ConcurrentHashMap<Integer, float[][]> samples = new ConcurrentHashMap<Integer, float[][]>();
  private final ConcurrentLinkedQueue<Cmd> queue = new ConcurrentLinkedQueue<Cmd>();
  private final Voice[] voices = new Voice[MAXV];
  private final Click[] clicks = new Click[MAXC];
  private final Strip[] strips = new Strip[256];
  private final float[] mixL = new float[MAXBLOCK];
  private final float[] mixR = new float[MAXBLOCK];
  private long frameCounter = 0;
  private int vRound = 0;

  // ---- comandos (hilo del plugin -> hilo de audio) ----
  static final class Cmd {
    int type; // 1 play, 2 click, 3 stop, 4 stopAll
    int sample, key, vid;
    float vol, eqL, eqM, eqH, rv, rel, amp;
    long recvNs; double delaySec;
    boolean accent;
  }

  public NAMixer(int sampleRate) {
    this.sr = sampleRate;
    for (int i = 0; i < MAXV; i++) voices[i] = new Voice();
    for (int i = 0; i < MAXC; i++) clicks[i] = new Click();
  }

  // ---- carga de samples ----
  public void loadFloat(int id, float[] l, float[] r, int srcRate) {
    if (srcRate != sr && srcRate > 0) {
      l = resample(l, srcRate);
      r = (r == l) ? l : resample(r, srcRate);
    }
    samples.put(id, new float[][] { l, r });
  }

  public void loadPcm16(int id, short[] data, int channels, int srcRate) {
    int n = data.length / Math.max(1, channels);
    float[] l = new float[n];
    float[] r = channels > 1 ? new float[n] : l;
    for (int i = 0; i < n; i++) {
      l[i] = data[i * channels] / 32768f;
      if (channels > 1) r[i] = data[i * channels + 1] / 32768f;
    }
    loadFloat(id, l, r, srcRate);
  }

  public boolean hasSample(int id) { return samples.containsKey(id); }
  public void removeSample(int id) { samples.remove(id); }

  private float[] resample(float[] in, int srcRate) {
    int n = (int) Math.round((double) in.length * sr / srcRate);
    float[] out = new float[Math.max(1, n)];
    double step = (double) srcRate / sr;
    for (int i = 0; i < out.length; i++) {
      double p = i * step;
      int i0 = (int) p;
      float f = (float) (p - i0);
      float a = i0 < in.length ? in[i0] : 0f;
      float b = (i0 + 1) < in.length ? in[i0 + 1] : a;
      out[i] = a + (b - a) * f;
    }
    return out;
  }

  // ---- API de comandos ----
  public void play(int sample, int key, int vid, float vol, float eqL, float eqM, float eqH, float rv, float rel, double delaySec) {
    Cmd c = new Cmd();
    c.type = 1; c.sample = sample; c.key = key & 255; c.vid = vid; c.vol = vol;
    c.eqL = eqL; c.eqM = eqM; c.eqH = eqH; c.rv = rv; c.rel = rel;
    c.recvNs = System.nanoTime(); c.delaySec = delaySec;
    queue.add(c);
  }

  public void click(boolean accent, double delaySec) {
    Cmd c = new Cmd();
    c.type = 2; c.accent = accent; c.recvNs = System.nanoTime(); c.delaySec = delaySec;
    queue.add(c);
  }

  public void stop(int vid) { Cmd c = new Cmd(); c.type = 3; c.vid = vid; queue.add(c); }
  public void stopAll() { Cmd c = new Cmd(); c.type = 4; queue.add(c); }
  public long getFrameCounter() { return frameCounter; }

  // ---- render (hilo de audio). out = estereo intercalado ----
  public void render(float[] out, int frames) {
    if (frames > MAXBLOCK) frames = MAXBLOCK;
    long blockNs = System.nanoTime();
    Cmd c;
    while ((c = queue.poll()) != null) apply(c, blockNs);
    renderBlock(out, frames);
  }

  /** Version para pruebas: los comandos llevan el instante relativo al inicio del bloque. */
  public void renderAt(float[] out, int frames, long blockNs) {
    Cmd c;
    while ((c = queue.poll()) != null) apply(c, blockNs);
    renderBlock(out, frames);
  }

  private void apply(Cmd c, long blockNs) {
    if (c.type == 1) {
      float[][] s = samples.get(c.sample);
      if (s == null) return;
      Strip st = strips[c.key];
      if (st == null) { st = new Strip(sr); strips[c.key] = st; }
      st.setParams(c.vol, c.eqL, c.eqM, c.eqH, c.rv);
      Voice v = pickVoice();
      v.l = s[0]; v.r = s[1]; v.len = s[0].length; v.pos = 0;
      v.limit = (c.rel > 0 && c.rel < 0.99f) ? Math.max(1, (int) (v.len * c.rel)) : v.len;
      v.wait = waitFrames(c, blockNs);
      v.fadeLeft = -1; v.vid = c.vid; v.strip = st; v.active = true; v.order = ++vRound;
    } else if (c.type == 2) {
      for (int i = 0; i < MAXC; i++) {
        Click k = clicks[i];
        if (!k.active) {
          k.active = true; k.t = 0; k.phase = 0; k.freq = c.accent ? 1200.0 : 800.0;
          k.amp = c.accent ? 0.5 : 0.25; k.wait = waitFrames(c, blockNs);
          break;
        }
      }
    } else if (c.type == 3) {
      int fl = Math.max(8, (int) (sr * 0.003));
      for (int i = 0; i < MAXV; i++) {
        Voice v = voices[i];
        if (v.active && v.vid == c.vid && v.fadeLeft < 0) { v.fadeLeft = fl; v.fadeLen = fl; }
      }
    } else if (c.type == 4) {
      int fl = Math.max(8, (int) (sr * 0.003));
      for (int i = 0; i < MAXV; i++) {
        Voice v = voices[i];
        if (v.active && v.fadeLeft < 0) { v.fadeLeft = fl; v.fadeLen = fl; }
      }
    }
  }

  private int waitFrames(Cmd c, long blockNs) {
    double ns = (double) (c.recvNs - blockNs) + c.delaySec * 1e9;
    long f = Math.round(ns * sr / 1e9);
    return f < 0 ? 0 : (int) Math.min(f, 10L * sr);
  }

  private Voice pickVoice() {
    Voice best = null;
    for (int i = 0; i < MAXV; i++) { if (!voices[i].active) return voices[i]; }
    for (int i = 0; i < MAXV; i++) { if (best == null || voices[i].order < best.order) best = voices[i]; }
    return best;
  }

  private void renderBlock(float[] out, int frames) {
    for (int i = 0; i < frames; i++) { mixL[i] = 0f; mixR[i] = 0f; }
    // voces -> tira de su pad
    for (int vi = 0; vi < MAXV; vi++) {
      Voice v = voices[vi];
      if (!v.active) continue;
      Strip st = v.strip;
      st.touched = true;
      int i = 0;
      if (v.wait > 0) { int sk = Math.min(v.wait, frames); v.wait -= sk; i = sk; }
      for (; i < frames; i++) {
        if (v.pos >= v.limit || v.pos >= v.len) { v.active = false; break; }
        float g = 1f;
        int fl = Math.max(8, (int) (sr * 0.002));
        int toEnd = v.limit - v.pos;
        if (v.limit < v.len && toEnd < fl) g = (float) toEnd / fl;
        if (v.fadeLeft >= 0) {
          if (v.fadeLeft == 0) { v.active = false; break; }
          g *= (float) v.fadeLeft / v.fadeLen;
          v.fadeLeft--;
        }
        st.dryL[i] += v.l[v.pos] * g;
        st.dryR[i] += v.r[v.pos] * g;
        v.pos++;
      }
    }
    for (int si = 0; si < strips.length; si++) {
      Strip st = strips[si];
      if (st == null) continue;
      if (st.touched) { st.tail = st.rv > 0 ? 4 * sr : 0; st.touched = false; st.processed = true; }
      else if (st.tail > 0) { st.tail -= frames; st.processed = true; }
      else { st.processed = false; }
      if (st.processed) st.process(frames, mixL, mixR);
    }
    // clics
    for (int ci = 0; ci < MAXC; ci++) {
      Click k = clicks[ci];
      if (!k.active) continue;
      int i = 0;
      if (k.wait > 0) { int sk = Math.min(k.wait, frames); k.wait -= sk; i = sk; }
      double step = 2.0 * Math.PI * k.freq / sr;
      for (; i < frames; i++) {
        double tt = k.t / (double) sr;
        if (tt >= 0.07) { k.active = false; break; }
        double g = k.amp * Math.pow(0.001 / k.amp, Math.min(tt, 0.06) / 0.06);
        float s = (float) (Math.sin(k.phase) * g);
        mixL[i] += s; mixR[i] += s;
        k.phase += step; k.t++;
      }
    }
    for (int i = 0; i < frames; i++) {
      float a = mixL[i], b = mixR[i];
      out[2 * i] = a > 1f ? 1f : (a < -1f ? -1f : a);
      out[2 * i + 1] = b > 1f ? 1f : (b < -1f ? -1f : b);
    }
    frameCounter += frames;
  }

  // ---------------- clases internas ----------------
  static final class Voice {
    float[] l, r; int len, pos, limit, wait, fadeLeft = -1, fadeLen, vid, order; boolean active; Strip strip;
  }

  static final class Click { boolean active; int wait; long t; double phase, freq, amp; }

  static final class Biquad {
    double b0 = 1, b1, b2, a1, a2; double z1L, z2L, z1R, z2R;
    void set(int type, double fs, double f0, double gainDb, double q) {
      // type 0 lowshelf, 1 peaking, 2 highshelf (formulas RBJ, igual que Web Audio)
      double A = Math.pow(10.0, gainDb / 40.0);
      double w0 = 2 * Math.PI * f0 / fs;
      double cw = Math.cos(w0), sw = Math.sin(w0);
      double n0, n1, n2, d0, d1, d2;
      if (type == 1) {
        double alpha = sw / (2 * q);
        n0 = 1 + alpha * A; n1 = -2 * cw; n2 = 1 - alpha * A;
        d0 = 1 + alpha / A; d1 = -2 * cw; d2 = 1 - alpha / A;
      } else {
        double alpha = sw / 2.0 * Math.sqrt(2.0); // pendiente S=1
        double sq = 2 * Math.sqrt(A) * alpha;
        if (type == 0) {
          n0 = A * ((A + 1) - (A - 1) * cw + sq);
          n1 = 2 * A * ((A - 1) - (A + 1) * cw);
          n2 = A * ((A + 1) - (A - 1) * cw - sq);
          d0 = (A + 1) + (A - 1) * cw + sq;
          d1 = -2 * ((A - 1) + (A + 1) * cw);
          d2 = (A + 1) + (A - 1) * cw - sq;
        } else {
          n0 = A * ((A + 1) + (A - 1) * cw + sq);
          n1 = -2 * A * ((A - 1) + (A + 1) * cw);
          n2 = A * ((A + 1) + (A - 1) * cw - sq);
          d0 = (A + 1) - (A - 1) * cw + sq;
          d1 = 2 * ((A - 1) - (A + 1) * cw);
          d2 = (A + 1) - (A - 1) * cw - sq;
        }
      }
      b0 = n0 / d0; b1 = n1 / d0; b2 = n2 / d0; a1 = d1 / d0; a2 = d2 / d0;
    }
    float runL(float x) { double y = b0 * x + z1L; z1L = b1 * x - a1 * y + z2L; z2L = b2 * x - a2 * y; return (float) y; }
    float runR(float x) { double y = b0 * x + z1R; z1R = b1 * x - a1 * y + z2R; z2R = b2 * x - a2 * y; return (float) y; }
  }

  static final class Strip {
    final int sr; final float[] dryL = new float[MAXBLOCK], dryR = new float[MAXBLOCK];
    final Biquad lo = new Biquad(), mid = new Biquad(), hi = new Biquad();
    float vol = 1f, eqL = 0f, eqM = 0f, eqH = 0f, rv = 0f; boolean init = false;
    final float[] dlL, dlR; int dIdx = 0; final int dLen;
    final float[] hL = new float[128], hR = new float[128]; int hIdx = 0; // Web Audio agrega 128 muestras al lazo de realimentacion
    boolean touched, processed; int tail;
    Strip(int sr) { this.sr = sr; dLen = Math.max(1, (int) Math.round(0.06 * sr)); dlL = new float[dLen]; dlR = new float[dLen]; }
    boolean live() { return touched || tail > 0; }
    void setParams(float v, float l, float m, float h, float r) {
      vol = v; rv = r;
      if (!init || l != eqL) lo.set(0, sr, 200, l, 1);
      if (!init || m != eqM) mid.set(1, sr, 1000, m, 1);
      if (!init || h != eqH) hi.set(2, sr, 4000, h, 1);
      eqL = l; eqM = m; eqH = h; init = true;
    }
    void clear(int n) { for (int i = 0; i < n; i++) { dryL[i] = 0f; dryR[i] = 0f; } }
    void process(int n, float[] outL, float[] outR) {
      float wet = Math.min(rv, 1f), fb = Math.min(rv * 0.5f, 0.85f);
      boolean useRv = rv > 0f || tail > 0;
      for (int i = 0; i < n; i++) {
        float xl = dryL[i], xr = dryR[i]; dryL[i] = 0f; dryR[i] = 0f;
        float l = hi.runL(mid.runL(lo.runL(xl)));
        float r = hi.runR(mid.runR(lo.runR(xr)));
        float yl = 0f, yr = 0f;
        if (useRv) {
          yl = dlL[dIdx]; yr = dlR[dIdx];
          float ql = hL[hIdx], qr = hR[hIdx]; hL[hIdx] = yl; hR[hIdx] = yr;
          hIdx++; if (hIdx >= 128) hIdx = 0;
          dlL[dIdx] = xl * wet + fb * ql; dlR[dIdx] = xr * wet + fb * qr;
          dIdx++; if (dIdx >= dLen) dIdx = 0;
        }
        outL[i] += (l + yl) * vol;
        outR[i] += (r + yr) * vol;
      }
    }
  }
}
