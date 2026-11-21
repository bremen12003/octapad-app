package PACKAGE_PLACEHOLDER;

import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.util.HashMap;
import java.util.Map;

@CapacitorPlugin(name = "NativeAudio")
public class NativeAudioPlugin extends Plugin {
  private NativeAudioEngine eng;
  private final Map<Integer, Pend> pend = new HashMap<Integer, Pend>();

  private static class Pend { short[] data; int off; int sr; int ch; }

  private JSObject info(boolean ok) {
    JSObject o = new JSObject();
    o.put("ok", ok);
    if (eng != null) {
      o.put("sr", eng.sr);
      o.put("burst", eng.burst);
      o.put("mult", eng.getMult());
      o.put("auto", eng.getAuto());
      o.put("lat", Math.round(eng.latencyMs() * 10.0) / 10.0);
      o.put("under", eng.getUnderruns());
      o.put("err", eng.getError());
    }
    return o;
  }

  @PluginMethod
  public void init(PluginCall call) {
    try {
      if (eng == null) eng = new NativeAudioEngine(getContext());
      eng.setMult(call.getInt("mult", 2));
      eng.setAuto(call.getBoolean("auto", true));
      boolean ok = eng.start();
      call.resolve(info(ok));
    } catch (Throwable t) {
      JSObject o = new JSObject();
      o.put("ok", false);
      o.put("err", String.valueOf(t));
      call.resolve(o);
    }
  }

  @PluginMethod
  public void getInfo(PluginCall call) { call.resolve(info(eng != null && eng.isRunning())); }

  @PluginMethod
  public void setMult(PluginCall call) {
    if (eng != null) eng.setMult(call.getInt("mult", 2));
    call.resolve(info(eng != null && eng.isRunning()));
  }

  @PluginMethod
  public void setAuto(PluginCall call) {
    if (eng != null) eng.setAuto(call.getBoolean("auto", true));
    call.resolve(info(eng != null && eng.isRunning()));
  }

  @PluginMethod
  public void sampleBegin(PluginCall call) {
    Pend p = new Pend();
    int frames = call.getInt("frames", 0);
    p.ch = Math.max(1, Math.min(2, call.getInt("ch", 1)));
    p.sr = call.getInt("sr", 48000);
    p.data = new short[frames * p.ch];
    p.off = 0;
    synchronized (pend) { pend.put(call.getInt("id", 0), p); }
    call.resolve();
  }

  @PluginMethod
  public void sampleChunk(PluginCall call) {
    Pend p;
    synchronized (pend) { p = pend.get(call.getInt("id", 0)); }
    if (p == null) { call.reject("sin inicio"); return; }
    byte[] b = Base64.decode(call.getString("data", ""), Base64.NO_WRAP);
    int n = b.length / 2;
    for (int i = 0; i < n && p.off < p.data.length; i++) {
      p.data[p.off++] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
    }
    call.resolve();
  }

  @PluginMethod
  public void sampleEnd(PluginCall call) {
    int id = call.getInt("id", 0);
    Pend p;
    synchronized (pend) { p = pend.remove(id); }
    if (p == null || eng == null) { call.reject("sin datos"); return; }
    eng.mixer.loadPcm16(id, p.data, p.ch, p.sr);
    call.resolve();
  }

  @PluginMethod(returnType = PluginMethod.RETURN_NONE)
  public void play(PluginCall call) {
    if (eng == null) return;
    eng.mixer.play(
        call.getInt("s", 0), call.getInt("k", 0), call.getInt("vid", 0),
        call.getFloat("v", 1f), call.getFloat("l", 0f), call.getFloat("m", 0f), call.getFloat("h", 0f),
        call.getFloat("rv", 0f), call.getFloat("rel", 1f), call.getDouble("d", 0.0) / 1000.0);
  }

  @PluginMethod(returnType = PluginMethod.RETURN_NONE)
  public void click(PluginCall call) {
    if (eng == null) return;
    eng.mixer.click(call.getBoolean("a", false), call.getDouble("d", 0.0) / 1000.0);
  }

  @PluginMethod(returnType = PluginMethod.RETURN_NONE)
  public void stopVoice(PluginCall call) {
    if (eng != null) eng.mixer.stop(call.getInt("vid", 0));
  }

  @PluginMethod(returnType = PluginMethod.RETURN_NONE)
  public void stopAll(PluginCall call) {
    if (eng != null) eng.mixer.stopAll();
  }

  @Override
  protected void handleOnPause() {
    super.handleOnPause();
    if (eng != null) eng.stop();
  }

  @Override
  protected void handleOnResume() {
    super.handleOnResume();
    if (eng != null) eng.start();
  }

  @Override
  protected void handleOnDestroy() {
    super.handleOnDestroy();
    if (eng != null) eng.stop();
  }
}
