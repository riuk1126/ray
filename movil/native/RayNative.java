package com.raiki.ray;

import android.Manifest;
import android.content.Intent;
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

  private void ev(String name, JSObject data) {
    notifyListeners(name, data == null ? new JSObject() : data);
  }

  @Override
  public void load() {
    tts = new TextToSpeech(getContext(), status -> {
      ttsReady = (status == TextToSpeech.SUCCESS);
      if (ttsReady) {
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
          @Override public void onStart(String id) { ev("speakStart", null); }
          @Override public void onDone(String id) { ev("speakEnd", null); }
          @Override public void onError(String id) { ev("speakEnd", null); }
        });
      }
    });
  }

  @Override
  protected void handleOnDestroy() {
    try { if (rec != null) { rec.destroy(); rec = null; } } catch (Exception e) { }
    try { if (tts != null) { tts.stop(); tts.shutdown(); } } catch (Exception e) { }
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

  private final RecognitionListener listener = new RecognitionListener() {
    @Override public void onReadyForSpeech(Bundle params) { JSObject d = new JSObject(); d.put("state", "ready"); ev("listenState", d); }
    @Override public void onBeginningOfSpeech() { JSObject d = new JSObject(); d.put("state", "speech"); ev("listenState", d); }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { JSObject d = new JSObject(); d.put("state", "end"); ev("listenState", d); }
    @Override public void onError(int error) { JSObject d = new JSObject(); d.put("code", error); ev("listenError", d); }
    @Override public void onResults(Bundle results) { JSObject d = new JSObject(); d.put("text", first(results)); ev("listenFinal", d); }
    @Override public void onPartialResults(Bundle partialResults) { JSObject d = new JSObject(); d.put("text", first(partialResults)); ev("listenPartial", d); }
    @Override public void onEvent(int eventType, Bundle params) { }
  };

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
        if (rec != null) { try { rec.cancel(); rec.destroy(); } catch (Exception e) { } rec = null; }
        rec = SpeechRecognizer.createSpeechRecognizer(getContext());
        rec.setRecognitionListener(listener);
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
    if (!ttsReady || text == null || text.isEmpty()) { call.reject("tts_not_ready"); return; }
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
      tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ray" + System.currentTimeMillis());
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
