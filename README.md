# Temporizador Cíclico

Aplicación Android nativa (Kotlin + Jetpack Compose), sin conexión a internet, con dos módulos independientes:

1. **Temporizador Cíclico**: avisos repetidos cada N minutos/segundos o con una secuencia (`1m, 30s, 2m`), con pausa, reanudación, detención y límite de avisos opcional.
2. **Prueba de Bombeo – Gasto Constante**: alarmas automáticas para tomar mediciones. Es **solo un temporizador**: no registra niveles, caudales ni datos.

## Secuencia de la prueba de bombeo

Calculada siempre desde el instante en que se pulsa **Iniciar** (minuto 0):

| Tiempo transcurrido | Frecuencia |
|---|---|
| 0–5 min | cada 1 min |
| 5–15 min | cada 2 min |
| 15–30 min | cada 5 min |
| 30–60 min | cada 10 min |
| 1–2 h | cada 15 min |
| 2–4 h | cada 30 min |
| desde 4 h | cada 60 min |

24 h → 44 avisos: 1, 2, 3, 4, 5, 7, 9, 11, 13, 15, 20, 25, 30, 40, 50, 60, 75, 90, 105, 120, 150, 180, 210, 240, 300, 360 … 1380, 1440.
El aviso del minuto 1440 indica «Prueba de bombeo finalizada». Duraciones de 6, 12, 24, 48 y 72 h o personalizadas
(más de 24 h: sigue cada 60 min; menos: termina con un único aviso final en la duración elegida).

## Instalar en el teléfono

1. Descarga `apk/TemporizadorCiclico-1.0.0-release.apk` (o la versión `-debug`) al teléfono.
2. Ábrela. HyperOS pedirá permitir «Instalar apps desconocidas» para el navegador/gestor de archivos: acéptalo.
3. Abre la app y concede el permiso de notificaciones.
4. Entra en **Comprobar alarmas** y deja todo en verde (ver abajo). Haz la prueba «En 1 min» con el teléfono bloqueado.

> Si ya tenías instalada una APK firmada con otra clave, desinstálala primero.

## Ajustes en Xiaomi HyperOS (importante)

En *Ajustes › Apps › Administrar apps › Temporizador Cíclico*:

- **Inicio automático**: activado.
- **Ahorro de batería**: «Sin restricciones» (botones **Quitar límite** y **Abrir ajustes** en la app).
- **Notificaciones**: permitidas, incluidas pantalla de bloqueo y flotantes.
- **Otros permisos**: «Mostrar en pantalla de bloqueo».
- **Alarmas y recordatorios**: permitido (normalmente ya viene concedido).
- En **Recientes**, bloquea la app con el candado.
- Sube el volumen de **alarma** y verifica que «No molestar» permita alarmas.

## Arquitectura de las alarmas

- Cada aviso se programa con `AlarmManager.setAlarmClock()`: la API de despertadores. Suena a la hora exacta con
  pantalla apagada y en Doze, y **no** tiene la limitación de ~9 min de `setExactAndAllowWhileIdle()` en reposo.
- Solo hay **una** alarma del sistema pendiente por módulo (la próxima). Al sonar, se programa la siguiente
  calculada como `inicio + minuto del programa`, así un retraso nunca desplaza las demás y no hay duplicados.
- El tiempo transcurrido se calcula con `elapsedRealtime` (inmune a cambios de hora); tras un reinicio se usa el reloj
  de pared. La interfaz solo lee ese valor; no cuenta segundos.
- Mientras haya un temporizador activo, un **servicio en primer plano** (tipo `specialUse`, sin límite de tiempo)
  mantiene una notificación con la cuenta regresiva y reduce el riesgo de que HyperOS cierre la app. El sonido
  (canal `USAGE_ALARM`) se emite desde ese servicio y se detiene tras los segundos configurados.
- Permisos: `USE_EXACT_ALARM`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE(_SPECIAL_USE)`, `RECEIVE_BOOT_COMPLETED`,
  `WAKE_LOCK`, `VIBRATE`, `USE_FULL_SCREEN_INTENT`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- Reinicio / actualización / cambio de hora: se restaura el estado y se reprograma la próxima alarma; los avisos que
  debieron sonar con el teléfono apagado se cuentan como «perdidos» y **no** se reproducen juntos.
- La app no modifica el volumen ni el modo «No molestar»; si detecta volumen de alarma en 0, «Silencio total» u otra
  restricción, lo advierte antes de iniciar.

## Limitaciones reales

- No puede sonar con el teléfono apagado.
- Tras reiniciar, las alarmas se restauran cuando el teléfono se desbloquea por primera vez.
- Si se fuerza la detención de la app (o HyperOS la cierra sin «Inicio automático»), Android borra sus alarmas.
- Con volumen de alarma en 0 o «No molestar» bloqueando alarmas no habrá sonido.
- La puntualidad depende del sistema: `setAlarmClock` es el mecanismo más preciso disponible, pero no existe una
  garantía absoluta en Android.

## Compilar

Requiere JDK 17+ y Android SDK 36.

```
./gradlew testDebugUnitTest   # pruebas
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # requiere keystore.properties (no incluido en el repositorio)
```
