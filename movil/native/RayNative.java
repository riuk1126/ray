package com.raiki.ray;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import java.util.HashSet;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import android.os.StatFs;
import com.google.mediapipe.tasks.genai.llminference.LlmInference;

// Puente de Ray: oído (reconocimiento de voz), voz (texto a voz) y cerebro (API de Claude)
@CapacitorPlugin(
  name = "RayNative",
  permissions = { @Permission(alias = "mic", strings = { Manifest.permission.RECORD_AUDIO }) }
)
public class RayNative extends Plugin {

  private SpeechRecognizer rec;
  private TextToSpeech tts;
  private volatile boolean ttsReady = false;
  private final Handler main = new Handler(Looper.getMainLooper());

  // Silenciar el pitido del micrófono: mientras Ray escucha se silencian los sonidos
  // del sistema; vuelven en cuanto Ray habla, se detiene o la app pasa a segundo plano.
  private final int[] BEEP = { AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM };
  private final HashSet<Integer> muted = new HashSet<>();
  private synchronized void muteBeep() {
    try {
      AudioManager am = (AudioManager) getContext().getSystemService(android.content.Context.AUDIO_SERVICE);
      for (int st : BEEP) {
        try { if (!muted.contains(st) && !am.isStreamMute(st)) { am.adjustStreamVolume(st, AudioManager.ADJUST_MUTE, 0); muted.add(st); } } catch (Exception e) { }
      }
    } catch (Exception e) { }
  }
  private synchronized void unmuteBeep() {
    try {
      AudioManager am = (AudioManager) getContext().getSystemService(android.content.Context.AUDIO_SERVICE);
      for (Integer st : muted) { try { am.adjustStreamVolume(st, AudioManager.ADJUST_UNMUTE, 0); } catch (Exception e) { } }
    } catch (Exception e) { }
    muted.clear();
  }

  @Override
  protected void handleOnPause() {
    super.handleOnPause();
    main.post(() -> { try { if (rec != null) { rec.cancel(); rec.destroy(); rec = null; } } catch (Exception e) { } unmuteBeep(); });
  }

  private void ev(String name, JSObject data) {
    notifyListeners(name, data == null ? new JSObject() : data);
  }

  @Override
  public void load() {
    tts = new TextToSpeech(getContext(), status -> {
      ttsReady = (status == TextToSpeech.SUCCESS);
      if (ttsReady) {
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
          @Override public void onStart(String id) { JSObject d = new JSObject(); d.put("id", id); ev("speakStart", d); }
          @Override public void onDone(String id) { JSObject d = new JSObject(); d.put("id", id); ev("speakEnd", d); }
          @Override public void onError(String id) { JSObject d = new JSObject(); d.put("id", id); ev("speakEnd", d); }
          // Android 8+: avisa qué parte del texto está pronunciando, para mover la boca palabra por palabra
          @Override public void onRangeStart(String id, int start, int end, int frame) { JSObject d = new JSObject(); d.put("id", id); d.put("s", start); d.put("e", end); ev("speakWord", d); }
        });
      }
    });
  }

  @Override
  protected void handleOnDestroy() {
    unmuteBeep();
    try { if (rec != null) { rec.destroy(); rec = null; } } catch (Exception e) { }
    try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Exception e) { }
    try { if (llm != null) { llm.close(); llm = null; } } catch (Exception e) { }
  }

  @PluginMethod
  public void info(PluginCall call) {
    JSObject r = new JSObject();
    try {
      r.put("version", getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0).versionName);
    } catch (Exception e) {
      r.put("version", "");
    }
    r.put("ttsReady", ttsReady);
    r.put("recognizer", SpeechRecognizer.isRecognitionAvailable(getContext()));
    r.put("locale", Locale.getDefault().toLanguageTag());
    call.resolve(r);
  }

  // Descarga el APK nuevo dentro de la app y abre el instalador de Android
  @PluginMethod
  public void installUpdate(final PluginCall call) {
    final String url = call.getString("url", "https://github.com/riuk1126/ray/releases/latest/download/Ray.apk");
    final Context ctx = getContext();
    if (Build.VERSION.SDK_INT >= 26 && !ctx.getPackageManager().canRequestPackageInstalls()) {
      try {
        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
      } catch (Exception e) { }
      JSObject r = new JSObject();
      r.put("status", "permission");
      call.resolve(r);
      return;
    }
    new Thread(() -> {
      try {
        File dir = new File(ctx.getCacheDir(), "update");
        dir.mkdirs();
        File f = new File(dir, "Ray.apk");
        String u = url;
        HttpURLConnection c = null;
        for (int k = 0; k < 6; k++) {
          c = (HttpURLConnection) new URL(u).openConnection();
          c.setInstanceFollowRedirects(false);
          c.setConnectTimeout(20000);
          c.setReadTimeout(60000);
          int code = c.getResponseCode();
          if (code >= 300 && code < 400) { u = new URL(new URL(u), c.getHeaderField("Location")).toString(); c.disconnect(); continue; }
          if (code != 200) throw new Exception("HTTP " + code);
          break;
        }
        long total = c.getContentLength();
        InputStream in = c.getInputStream();
        FileOutputStream out = new FileOutputStream(f);
        byte[] buf = new byte[65536];
        long got = 0; int n; int last = -1;
        while ((n = in.read(buf)) > 0) {
          out.write(buf, 0, n);
          got += n;
          if (total > 0) {
            int pc = (int) (got * 100 / total);
            if (pc != last) { last = pc; JSObject p = new JSObject(); p.put("p", pc); notifyListeners("updProgress", p); }
          }
        }
        out.close(); in.close(); c.disconnect();
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", f);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
        JSObject r = new JSObject();
        r.put("status", "installing");
        call.resolve(r);
      } catch (Exception e) {
        call.reject(String.valueOf(e.getMessage()));
      }
    }).start();
  }

  // ---------- Micrófono ----------
  @PluginMethod
  public void askMic(PluginCall call) {
    if (getPermissionState("mic") == PermissionState.GRANTED) {
      JSObject r = new JSObject();
      r.put("granted", true);
      call.resolve(r);
    } else {
      requestPermissionForAlias("mic", call, "micResult");
    }
  }

  @PermissionCallback
  private void micResult(PluginCall call) {
    JSObject r = new JSObject();
    r.put("granted", getPermissionState("mic") == PermissionState.GRANTED);
    call.resolve(r);
  }

  // Un oyente por cada micrófono: los avisos de uno viejo ya no afectan al actual
  private RecognitionListener listenerFor(final SpeechRecognizer mine) {
    return new RecognitionListener() {
      private boolean cur() { return rec == mine; }
      @Override public void onReadyForSpeech(Bundle params) { if (!cur()) return; JSObject d = new JSObject(); d.put("state", "ready"); ev("listenState", d); }
      @Override public void onBeginningOfSpeech() { if (!cur()) return; JSObject d = new JSObject(); d.put("state", "speech"); ev("listenState", d); }
      @Override public void onRmsChanged(float rmsdB) { }
      @Override public void onBufferReceived(byte[] buffer) { }
      @Override public void onEndOfSpeech() { if (!cur()) return; JSObject d = new JSObject(); d.put("state", "end"); ev("listenState", d); }
      @Override public void onError(int error) { if (!cur()) return; JSObject d = new JSObject(); d.put("code", error); ev("listenError", d); }
      @Override public void onResults(Bundle results) { if (!cur()) return; JSObject d = new JSObject(); d.put("text", first(results)); ev("listenFinal", d); }
      @Override public void onPartialResults(Bundle partialResults) { if (!cur()) return; JSObject d = new JSObject(); d.put("text", first(partialResults)); ev("listenPartial", d); }
      @Override public void onEvent(int eventType, Bundle params) { }
    };
  }


  private static String first(Bundle b) {
    if (b == null) return "";
    ArrayList<String> l = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
    return (l == null || l.isEmpty() || l.get(0) == null) ? "" : l.get(0);
  }

  @PluginMethod
  public void listen(final PluginCall call) {
    final String lang = call.getString("lang", "es-PE");
    main.post(() -> {
      try {
        if (!SpeechRecognizer.isRecognitionAvailable(getContext())) { call.reject("no_recognizer"); return; }
        muteBeep();
        if (rec != null) {
          try { rec.cancel(); } catch (Exception e) { try { rec.destroy(); } catch (Exception e2) { } rec = null; }
        }
        if (rec == null) {
          SpeechRecognizer mine = SpeechRecognizer.createSpeechRecognizer(getContext());
          rec = mine;
          mine.setRecognitionListener(listenerFor(mine));
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getContext().getPackageName());
        rec.startListening(i);
        call.resolve();
      } catch (Exception e) {
        call.reject(String.valueOf(e.getMessage()));
      }
    });
  }

  @PluginMethod
  public void stopListen(final PluginCall call) {
    main.post(() -> {
      try { if (rec != null) { rec.cancel(); rec.destroy(); rec = null; } } catch (Exception e) { }
      if (!call.getBoolean("keepMuted", false)) unmuteBeep();
      call.resolve();
    });
  }

  // ---------- Voz ----------
  @PluginMethod
  public void voices(PluginCall call) {
    JSObject r = new JSObject();
    JSArray list = new JSArray();
    r.put("ready", ttsReady);
    try {
      if (ttsReady) {
        Set<Voice> vs = tts.getVoices();
        if (vs != null) {
          for (Voice v : vs) {
            if (v == null || v.getLocale() == null) continue;
            Set<String> f = v.getFeatures();
            if (f != null && f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
            JSObject o = new JSObject();
            o.put("name", v.getName());
            o.put("locale", v.getLocale().toLanguageTag());
            o.put("label", v.getLocale().getDisplayName(Locale.getDefault()));
            o.put("network", v.isNetworkConnectionRequired());
            list.put(o);
          }
        }
      }
    } catch (Exception e) { }
    r.put("voices", list);
    call.resolve(r);
  }

  @PluginMethod
  public void speak(PluginCall call) {
    String text = call.getString("text", "");
    String voiceName = call.getString("voice", "");
    String lang = call.getString("lang", "es-PE");
    Float rate = call.getFloat("rate", 1.0f);
    Float pitch = call.getFloat("pitch", 1.0f);
    Boolean queue = call.getBoolean("queue", false);
    String uid = call.getString("id", "");
    if (uid == null || uid.isEmpty()) uid = "ray" + System.currentTimeMillis();
    if (!ttsReady || text == null || text.isEmpty()) { call.reject("tts_not_ready"); return; }
    unmuteBeep();
    try {
      boolean set = false;
      if (voiceName != null && !voiceName.isEmpty()) {
        Set<Voice> vs = tts.getVoices();
        if (vs != null) {
          for (Voice v : vs) {
            if (v != null && voiceName.equals(v.getName())) { tts.setVoice(v); set = true; break; }
          }
        }
      }
      if (!set) tts.setLanguage(Locale.forLanguageTag(lang));
      tts.setSpeechRate(rate == null ? 1.0f : rate);
      tts.setPitch(pitch == null ? 1.0f : pitch);
      tts.speak(text, (queue != null && queue) ? TextToSpeech.QUEUE_ADD : TextToSpeech.QUEUE_FLUSH, null, uid);
      call.resolve();
    } catch (Exception e) {
      call.reject(String.valueOf(e.getMessage()));
    }
  }

  @PluginMethod
  public void stopSpeak(PluginCall call) {
    try { if (tts != null) tts.stop(); } catch (Exception e) { }
    call.resolve();
  }

  // ---------- Cerebro local (sin internet, como OpenJarvis) ----------
  // Un modelo pequeño (Qwen 2.5) corre dentro del teléfono con MediaPipe LLM Inference.
  // El archivo del modelo se descarga una sola vez desde Ajustes y queda en la memoria interna de la app.
  private LlmInference llm;
  private String llmFile = "";
  private volatile boolean llmDownloading = false;
  private final Object llmLock = new Object();

  private File llmDir() { File d = new File(getContext().getFilesDir(), "cerebro"); d.mkdirs(); return d; }
  private static boolean safeName(String n) { return n != null && n.matches("[A-Za-z0-9._\\-]+\\.task"); }

  @PluginMethod
  public void localInfo(PluginCall call) {
    JSObject r = new JSObject();
    JSArray files = new JSArray();
    File[] fs = llmDir().listFiles();
    if (fs != null) for (File f : fs) {
      if (f.getName().endsWith(".task")) { JSObject o = new JSObject(); o.put("name", f.getName()); o.put("size", f.length()); files.put(o); }
    }
    r.put("files", files);
    r.put("loaded", llm != null ? llmFile : "");
    r.put("downloading", llmDownloading);
    try { StatFs st = new StatFs(getContext().getFilesDir().getPath()); r.put("free", st.getAvailableBytes()); } catch (Exception e) { r.put("free", -1); }
    call.resolve(r);
  }

  // Descarga el modelo con reanudación: si se corta, al volver a tocar sigue donde quedó
  @PluginMethod
  public void localDownload(final PluginCall call) {
    final String url = call.getString("url", "");
    final String name = call.getString("name", "");
    if (url == null || !url.startsWith("https://huggingface.co/") || !safeName(name)) { call.reject("parametros"); return; }
    if (llmDownloading) { call.reject("ya_descargando"); return; }
    llmDownloading = true;
    new Thread(() -> {
      HttpURLConnection c = null;
      try {
        File done = new File(llmDir(), name), part = new File(llmDir(), name + ".part");
        if (done.exists() && done.length() > 0) { JSObject r = new JSObject(); r.put("status", "ok"); r.put("size", done.length()); call.resolve(r); return; }
        long have = part.exists() ? part.length() : 0;
        String u = url;
        int code = 0;
        for (int k = 0; k < 8; k++) {
          c = (HttpURLConnection) new URL(u).openConnection();
          c.setInstanceFollowRedirects(false);
          c.setConnectTimeout(20000);
          c.setReadTimeout(60000);
          if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
          code = c.getResponseCode();
          if (code >= 300 && code < 400) { u = new URL(new URL(u), c.getHeaderField("Location")).toString(); c.disconnect(); continue; }
          break;
        }
        if (code == 416) { have = 0; part.delete(); throw new Exception("reintentar"); }
        if (code != 200 && code != 206) throw new Exception("HTTP " + code);
        if (code == 200) have = 0; // el servidor no aceptó reanudar: empieza de cero
        long len = c.getContentLengthLong();
        long total = len > 0 ? have + len : -1;
        try {
          StatFs st = new StatFs(getContext().getFilesDir().getPath());
          if (len > 0 && st.getAvailableBytes() < len + 200L * 1024 * 1024) throw new Exception("sin_espacio");
        } catch (IllegalArgumentException e) { }
        InputStream in = c.getInputStream();
        FileOutputStream out = new FileOutputStream(part, have > 0);
        byte[] buf = new byte[131072];
        long got = have; int n; int last = -1;
        while ((n = in.read(buf)) > 0) {
          out.write(buf, 0, n);
          got += n;
          if (total > 0) {
            int pc = (int) (got * 100 / total);
            if (pc != last) { last = pc; JSObject p = new JSObject(); p.put("p", pc); p.put("got", got); p.put("total", total); notifyListeners("llmProgress", p); }
          }
        }
        out.close(); in.close();
        if (total > 0 && got < total) throw new Exception("incompleto");
        if (!part.renameTo(done)) throw new Exception("no_guardado");
        JSObject r = new JSObject(); r.put("status", "ok"); r.put("size", done.length());
        call.resolve(r);
      } catch (Exception e) {
        call.reject(String.valueOf(e.getMessage()));
      } finally {
        if (c != null) c.disconnect();
        llmDownloading = false;
      }
    }).start();
  }

  @PluginMethod
  public void localDelete(PluginCall call) {
    // keep: nombre de un modelo que no se borra (al cambiar de modelo se borra solo el anterior)
    final String keep = call.getString("keep", "");
    if (llmDownloading) { call.reject("descargando"); return; }
    synchronized (llmLock) {
      if (keep == null || keep.isEmpty() || !keep.equals(llmFile)) {
        try { if (llm != null) llm.close(); } catch (Exception e) { }
        llm = null; llmFile = "";
      }
      File[] fs = llmDir().listFiles();
      if (fs != null) for (File f : fs) { if (keep == null || !f.getName().equals(keep)) f.delete(); }
    }
    call.resolve();
  }

  // Responde con el modelo del teléfono. Solo uno a la vez.
  @PluginMethod
  public void localChat(final PluginCall call) {
    final String name = call.getString("name", "");
    final String prompt = call.getString("prompt", "");
    final Integer maxTokens = call.getInt("maxTokens", 1280);
    if (!safeName(name) || prompt == null || prompt.isEmpty()) { call.reject("parametros"); return; }
    new Thread(() -> {
      synchronized (llmLock) {
        try {
          File f = new File(llmDir(), name);
          if (!f.exists()) { call.reject("sin_modelo"); return; }
          if (llm == null || !name.equals(llmFile)) {
            try { if (llm != null) llm.close(); } catch (Exception e) { }
            llm = null;
            LlmInference.LlmInferenceOptions opts = LlmInference.LlmInferenceOptions.builder()
              .setModelPath(f.getAbsolutePath())
              .setMaxTokens(maxTokens == null ? 1280 : maxTokens)
              .setMaxTopK(40)
              .build();
            llm = LlmInference.createFromOptions(getContext(), opts);
            llmFile = name;
          }
          String out = llm.generateResponse(prompt);
          JSObject r = new JSObject();
          r.put("text", out == null ? "" : out);
          call.resolve(r);
        } catch (Throwable e) {
          try { if (llm != null) llm.close(); } catch (Exception e2) { }
          llm = null; llmFile = "";
          call.reject(String.valueOf(e.getMessage()), "local");
        }
      }
    }).start();
  }

  // ---------- Cerebro ----------
  // provider "gemini" (Google, con nivel gratuito) o "claude" (Anthropic, de pago por uso)
  @PluginMethod
  public void chat(final PluginCall call) {
    final String provider = call.getString("provider", "gemini");
    final String key = call.getString("key", "");
    final String model = call.getString("model", "");
    final String system = call.getString("system", "");
    final JSArray messages = call.getArray("messages", new JSArray());
    final Integer maxTokens = call.getInt("maxTokens", 700);
    new Thread(() -> {
      HttpURLConnection c = null;
      try {
        final boolean gemini = !"claude".equals(provider);
        JSONArray ms = new JSONArray(messages.toString());
        JSONObject body = new JSONObject();
        String url;
        if (gemini) {
          JSONArray contents = new JSONArray();
          for (int k = 0; k < ms.length(); k++) {
            JSONObject m = ms.getJSONObject(k);
            JSONObject o = new JSONObject();
            o.put("role", "assistant".equals(m.optString("role")) ? "model" : "user");
            o.put("parts", new JSONArray().put(new JSONObject().put("text", m.optString("content", ""))));
            contents.put(o);
          }
          body.put("system_instruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system))));
          body.put("contents", contents);
          body.put("generationConfig", new JSONObject().put("maxOutputTokens", 4096));
          url = "https://generativelanguage.googleapis.com/v1beta/models/" + (model == null || model.isEmpty() ? "gemini-flash-latest" : model) + ":generateContent";
        } else {
          body.put("model", model == null || model.isEmpty() ? "claude-sonnet-5-5" : model);
          body.put("max_tokens", maxTokens == null ? 700 : maxTokens);
          body.put("system", system);
          body.put("messages", ms);
          url = "https://api.anthropic.com/v1/messages";
        }
        c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(90000);
        c.setDoOutput(true);
        c.setRequestProperty("content-type", "application/json");
        if (gemini) {
          c.setRequestProperty("x-goog-api-key", key);
        } else {
          c.setRequestProperty("x-api-key", key);
          c.setRequestProperty("anthropic-version", "2023-06-01");
        }
        OutputStream out = c.getOutputStream();
        out.write(body.toString().getBytes("UTF-8"));
        out.close();
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) {
          byte[] buf = new byte[8192];
          int n;
          while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
          in.close();
        }
        String raw = bo.toString("UTF-8");
        if (code >= 400) {
          String msg = "";
          try { msg = new JSONObject(raw).getJSONObject("error").optString("message", ""); } catch (Exception e) { }
          call.reject(msg.isEmpty() ? ("HTTP " + code) : msg, String.valueOf(code));
          return;
        }
        StringBuilder sb = new StringBuilder();
        JSONObject res = new JSONObject(raw);
        if (gemini) {
          JSONArray cands = res.optJSONArray("candidates");
          JSONObject content = (cands == null || cands.length() == 0) ? null : cands.optJSONObject(0).optJSONObject("content");
          JSONArray parts = content == null ? null : content.optJSONArray("parts");
          if (parts != null) {
            for (int k = 0; k < parts.length(); k++) {
              JSONObject b = parts.optJSONObject(k);
              if (b != null && !b.optBoolean("thought", false)) sb.append(b.optString("text", ""));
            }
          }
        } else {
          JSONArray content = res.optJSONArray("content");
          if (content != null) {
            for (int k = 0; k < content.length(); k++) {
              JSONObject b = content.optJSONObject(k);
              if (b != null && "text".equals(b.optString("type"))) sb.append(b.optString("text", ""));
            }
          }
        }
        JSObject r = new JSObject();
        r.put("text", sb.toString());
        call.resolve(r);
      } catch (Exception e) {
        call.reject(String.valueOf(e.getMessage()), "red");
      } finally {
        if (c != null) c.disconnect();
      }
    }).start();
  }
}
