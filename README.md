# QA Remote

Controla un móvil Android desde el PC por ADB (toques, gestos, texto, lectura de pantalla) cuando el fabricante bloquea `adb shell input`.

## Por qué

En algunos móviles (sobre todo Xiaomi con MIUI/HyperOS) `adb shell input tap ...` falla con:

```
java.lang.SecurityException: Injecting input events requires the caller (or the source of the instrumentation, if any) to have the INJECT_EVENTS permission.
```

Se arregla activando "Depuración USB (ajustes de seguridad)", pero esa opción exige cuenta Mi, SIM y a veces no está disponible. QA Remote evita el problema: una app con un **AccessibilityService** ejecuta los gestos con `dispatchGesture` y las acciones sobre nodos (click, escribir texto), y el PC le da órdenes con `adb shell am broadcast`.

## Compilar

Requisitos: Android SDK (`%LOCALAPPDATA%\Android\Sdk`, con `local.properties` apuntando a él) y el JDK incluido en Android Studio.

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleRelease
# APK: app\build\outputs\apk\release\app-release.apk (firmado, R8 activado)
```

### Keystore de firma

El keystore y su contraseña **no están en git** (`keystore/` y `keystore.properties` en `.gitignore`). Para regenerarlos:

```powershell
$pw = -join ((48..57)+(65..90)+(97..122) | Get-Random -Count 32 | % {[char]$_})
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -keystore keystore\qaremote-release.jks -storetype PKCS12 `
  -alias qaremote -keyalg RSA -keysize 3072 -validity 10000 -storepass $pw -keypass $pw -dname "CN=QA Remote, O=Local"
"storeFile=keystore/qaremote-release.jks`nstorePassword=$pw`nkeyAlias=qaremote`nkeyPassword=$pw" | Out-File -Encoding ascii keystore.properties
cipher /e keystore keystore\qaremote-release.jks keystore.properties   # cifrado EFS en disco
```

Si cambias el keystore, desinstala la versión anterior del móvil antes de instalar (firma distinta).
Sin `keystore.properties` el build release sale sin firmar.

## Instalación en el móvil

- Normal: `adb install app-release.apk`.
- Si MIUI bloquea también "Instalar vía USB": copia el APK al móvil (o descárgalo desde el navegador del móvil, p. ej. desde un servidor local o la nube) y ábrelo desde el gestor de archivos permitiendo "orígenes desconocidos".

## Activar

1. Abre **QA Remote** y concede el permiso de notificaciones.
2. Pulsa **Abrir ajustes de accesibilidad** → activa *QA Remote* (en Xiaomi: *Accesibilidad › Servicios descargados*). En Android 13+ con APK instalado fuera de una tienda puede que tengas que ir a *Información de la app › ⋮ › Permitir ajustes restringidos* primero.
3. Aparece una notificación fija: *"QA Remote activo · el PC puede controlar este móvil"*.

## Comandos

PowerShell (`qa-remote.ps1`) y Node (`qa-remote.mjs`, sin dependencias) aceptan lo mismo. Opciones: `-Serial`/`--serial`, `-Ms`/`--ms`, `-Out`/`--out`.

| Comando | Ejemplo PowerShell | Ejemplo Node |
|---|---|---|
| tap | `.\qa-remote.ps1 tap 540 1200` | `node qa-remote.mjs tap 540 1200` |
| longtap | `.\qa-remote.ps1 longtap 540 1200 -Ms 1000` | `node qa-remote.mjs longtap 540 1200 --ms 1000` |
| swipe | `.\qa-remote.ps1 swipe 540 1800 540 600 -Ms 400` | `node qa-remote.mjs swipe 540 1800 540 600` |
| text | `.\qa-remote.ps1 text "Hola, ¿qué tal?"` | `node qa-remote.mjs text "Hola, ¿qué tal?"` |
| tapText | `.\qa-remote.ps1 tapText "Iniciar sesión"` | `node qa-remote.mjs tapText "iniciar sesion"` |
| tapId | `.\qa-remote.ps1 tapId login_button` | `node qa-remote.mjs tapId com.app:id/login_button` |
| wait | `.\qa-remote.ps1 wait "Bienvenido" -Ms 10000` | `node qa-remote.mjs wait Bienvenido --ms 10000` |
| dump | `.\qa-remote.ps1 dump -Out arbol.json` | `node qa-remote.mjs dump` |
| screenshot | `.\qa-remote.ps1 screenshot -Out p.png` | `node qa-remote.mjs screenshot p.png` |
| back / home / recents | `.\qa-remote.ps1 back` | `node qa-remote.mjs home` |

Detalles:

- **tapText** busca un nodo visible cuyo `text` o `contentDescription` coincida: primero exacto, luego "contiene"; sin distinguir mayúsculas ni tildes. Hace `ACTION_CLICK` en el nodo o su primer ancestro clicable; si no hay, toca el centro de sus bounds.
- **text** escribe (reemplaza) en el campo con foco de entrada (`ACTION_SET_TEXT`). Toca antes el campo.
- **wait** espera hasta `ms` (máx. 25 s) a que aparezca un texto.
- **dump** guarda el árbol visible (clase, text, desc, viewId, bounds, clickable) en `/sdcard/Android/data/dev.qaremote/files/dump.json`, lo descarga con `adb pull` y lo imprime. También va a logcat (`adb logcat -s QAREMOTE`).
- Los textos viajan en base64 (extra `b64`), así que espacios, comillas y tildes no dan problemas.
- Salida: `OK: ...` (exit 0) o `ERROR: ...` (exit 1). Por debajo, cada comando contesta en el broadcast ordenado (`result=0|1`, `data="..."`) y en logcat con tag `QAREMOTE`.

Uso directo sin scripts:

```
adb shell am broadcast -n dev.qaremote/.CommandReceiver -a dev.qaremote.CMD --es cmd tap --es x 540 --es y 1200
```

## Pantalla encendida durante la prueba

```powershell
.\qa-remote.ps1 awake on -Serial X      # bloqueo a 30 min + siempre encendida con USB
.\qa-remote.ps1 awake status -Serial X  # valor actual y original guardado
.\qa-remote.ps1 awake off -Serial X     # restaura el bloqueo original y quita "siempre encendida"
```

- `awake on` guarda el tiempo de bloqueo original del dispositivo en `%LOCALAPPDATA%\qa-remote\awake-<serial>.txt` (solo la primera vez, para no pisarlo si se repite) y `awake off` lo restaura y borra el fichero. Si no hay original guardado, restaura 30 s.
- Usa `adb` directamente (`settings put system screen_off_timeout` y `svc power stayon usb`), que funciona aunque el fabricante bloquee la inyección de toques. No necesita la app.
- **Ejecuta siempre `awake off` al terminar**, también si la prueba falla (por ejemplo en un `finally`).
- Mismo comando en Node: `node qa-remote.mjs awake on|off|status --serial X`.

## Grabar la pantalla

`.\record.ps1 [-Seconds 120] [-Out recordings\prueba.mp4] [-Serial X]`

- Usa **scrcpy** si está en el PATH o en `tools\` (gitignored). Descarga: https://github.com/Genymobile/scrcpy/releases → `scrcpy-win64-vX.Y.zip`, descomprimir en `tools\scrcpy\`.
- Si no, usa `adb screenrecord` en tramos de 180 s encadenados (`...-part01.mp4`, `-part02.mp4`, ...).

## Seguridad

- El receptor de órdenes está exportado pero protegido con `android:permission="android.permission.DUMP"`: solo el shell de ADB y el sistema tienen ese permiso; **ninguna app instalada puede enviarle órdenes**.
- **Sin permiso INTERNET** y sin puertos: no hay control remoto por red, solo por cable/ADB autorizado.
- No guarda datos del usuario; solo el último `dump.json` en el directorio privado de la app (se sobrescribe). Sin backup (`allowBackup=false`).
- Mientras el servicio está activo hay una **notificación visible** con botón *Desactivar*.
- Un servicio de accesibilidad puede leer y tocar todo lo que hay en pantalla: **desactívalo al terminar** y desinstala la app cuando no la necesites.

## Limitaciones

- `FLAG_SECURE` (apps bancarias, campos de contraseña) impide capturas y grabación, pero **no** los gestos.
- WebView y Flutter: los textos solo son localizables si la app expone semántica de accesibilidad (Flutter lo hace con `Semantics`/textos normales; WebView expone el DOM accesible). Si `tapText` no encuentra algo, usa `dump` para ver qué hay o toca por coordenadas.
- Los gestos se ejecutan con un solo dedo (no hay pinch/zoom multitouch).
- Algunas ROMs matan servicios en segundo plano: quita la optimización de batería a QA Remote si se desactiva solo.
