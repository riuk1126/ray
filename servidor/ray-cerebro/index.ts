// Ray — cerebro. Recibe la conversación de la app y la responde con Gemini.
// La clave vive aquí como secreto (GEMINI_API_KEY); la app nunca la ve ni la pide.
// Con ?stream=1 devuelve la respuesta por partes (SSE) para que Ray empiece a hablar antes.
// Un mensaje puede traer "img" (JPEG en base64): es lo que ve la cámara en ese momento.
import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const CORS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "content-type, authorization, apikey, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { ...CORS, "Content-Type": "application/json" } });
}

// Freno simple contra abuso: máximo de mensajes por minuto desde una misma dirección
const hits = new Map<string, number[]>();
function allowed(ip: string): boolean {
  const now = Date.now();
  const list = (hits.get(ip) || []).filter((t) => now - t < 60000);
  if (list.length >= 20) { hits.set(ip, list); return false; }
  list.push(now);
  hits.set(ip, list);
  return true;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: { message: "Método no permitido", code: 405 } }, 405);

  const key = Deno.env.get("GEMINI_API_KEY");
  if (!key) return json({ error: { message: "Falta configurar la clave del cerebro", code: "sin_clave" } }, 503);

  const ip = (req.headers.get("x-forwarded-for") || "").split(",")[0].trim() || "desconocido";
  if (!allowed(ip)) return json({ error: { message: "Demasiados mensajes seguidos", code: 429 } }, 429);

  let b: any;
  try { b = await req.json(); } catch { return json({ error: { message: "Petición inválida", code: 400 } }, 400); }

  const stream = new URL(req.url).searchParams.get("stream") === "1";
  const model = /^gemini-[a-z0-9.\-]+$/.test(String(b?.model || "")) ? String(b.model) : "gemini-3.5-flash-lite";
  const system = String(b?.system || "").slice(0, 16000);
  const msgs = Array.isArray(b?.messages) ? b.messages.slice(-24) : [];
  const contents = msgs.map((m: any) => {
    const parts: any[] = [];
    const img = typeof m?.img === "string" ? m.img : "";
    if (img && img.length < 2_000_000) parts.push({ inline_data: { mime_type: "image/jpeg", data: img } });
    parts.push({ text: String(m?.content || "").slice(0, 4000) || "(sin texto)" });
    return { role: m?.role === "assistant" ? "model" : "user", parts };
  });
  if (!contents.length) return json({ error: { message: "Sin mensajes", code: 400 } }, 400);

  const payload = JSON.stringify({
    system_instruction: { parts: [{ text: system }] },
    contents,
    generationConfig: { maxOutputTokens: 1200, thinkingConfig: { thinkingLevel: /lite/.test(model) ? "minimal" : "low" } },
  });
  const base = "https://generativelanguage.googleapis.com/v1beta/models/" + model;

  try {
    const r = await fetch(base + (stream ? ":streamGenerateContent?alt=sse" : ":generateContent"), {
      method: "POST",
      headers: { "content-type": "application/json", "x-goog-api-key": key },
      body: payload,
    });
    if (!r.ok) {
      const data: any = await r.json().catch(() => ({}));
      return json({ error: { message: data?.error?.message || "Error del cerebro", code: r.status } }, r.status);
    }
    if (stream) return new Response(r.body, { headers: { ...CORS, "Content-Type": "text/event-stream", "Cache-Control": "no-cache" } });
    const data: any = await r.json().catch(() => ({}));
    const parts = data?.candidates?.[0]?.content?.parts || [];
    const text = parts.filter((p: any) => !p?.thought).map((p: any) => p?.text || "").join("");
    return json({ text });
  } catch (_e) {
    return json({ error: { message: "No se pudo contactar al cerebro", code: 502 } }, 502);
  }
});
