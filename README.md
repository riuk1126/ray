# Ray

Asistente de voz con carita para Android.

- `web/` — la app (carita, conversación y configuración).
- `movil/` — envoltura de Android (Capacitor) y el puente nativo de voz y cerebro.
- `servidor/ray-cerebro/` — el cerebro sin clave (función de Supabase). La clave de Gemini vive ahí como secreto `GEMINI_API_KEY`.
- Cada cambio en `main` arma el APK y lo publica en Releases como `Ray.apk`.

Descarga: https://github.com/riuk1126/ray/releases/latest/download/Ray.apk
