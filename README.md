# KalinDroid

Cliente Android para ejecutar Kali Linux NetHunter sobre `proot`, sin root,
con terminal integrada basada en xterm.js.

La app descarga y extrae un rootfs de Kali NetHunter 2026.3, arranca `zsh`
dentro del contenedor y permite instalar paquetes con `apt`. El proyecto está
pensado para Android arm64 y se compila sin Gradle.

## Estado

- Versión: `1.3` (`versionCode 4`).
- `minSdk 26`, `targetSdk 28`.
- ABI: `arm64-v8a`.
- Probado en Termux sobre Android 10.

## Estructura

```text
app/
  AndroidManifest.xml
  assets/          UI, xterm.js, configuración y binarios host
  java/            Actividad, servicio, puente PTY y WebView
  jni/pty.c        Implementación nativa del PTY
  res/             Recursos Android
build.sh           Compilación, empaquetado y firma
.gitignore         Excluye claves, builds y herramientas locales
```

## Requisitos de compilación

En Termux están disponibles las herramientas necesarias. El script comprueba
que existan:

- `aapt2`
- `d8`
- `apksigner`
- `keytool`
- `javac`
- `clang`
- `zip`

También necesitas un `android.jar` de **Android SDK Platform 29**.

Este repositorio no incluye `.tools/android.jar` por tamaño y licencia. Cópialo
así:

```bash
mkdir -p .tools
cp /ruta/al/android.jar .tools/android.jar
```

Puedes obtenerlo con el SDK de Android:

```bash
sdkmanager "platforms;android-29"
# platforms/android-29/android.jar
```

El build está probado en Termux arm64. En otra distribución Linux necesitarás
un `clang` con sysroot de Android y ejecutar el script con `bash build.sh`.

## Clave de firma

`build.sh` usa:

```text
.keys/kalindroid.jks
.keys/password
```

Estos archivos **no se suben al repositorio** y están excluidos por
`.gitignore`. Guárdalos en un gestor de contraseñas y en copias cifradas
separadas.

Si falta `.keys/kalindroid.jks`, el build se detiene. Para generar una clave
nueva de desarrollo:

```bash
ALLOW_NEW_KEY=1 ./build.sh
```

Una APK firmada con una clave nueva **no puede actualizar** una instalación
hecha con la clave anterior. Habría que desinstalar y reinstalar, perdiendo los
datos de la app y el rootfs de Kali.

## Compilar

```bash
git clone https://github.com/m404rce/KalinDroid.git
cd KalinDroid

mkdir -p .tools
cp /ruta/al/android.jar .tools/android.jar

# Restaura .keys/ desde tu copia segura antes de continuar.
./build.sh
```

En Termux el shebang del script es válido. En otras distribuciones usa:

```bash
bash build.sh
```

La APK se genera en:

```text
build/KalinDroid-1.3.apk
```

## Verificar la APK

```bash
apksigner verify --print-certs build/KalinDroid-1.3.apk
sha256sum build/KalinDroid-1.3.apk
```

Compara la huella del certificado con la que tengas guardada. Así confirmas que
la APK se firmó con tu clave y no con una nueva.

## Recuperación desde GitHub

Si pierdes la copia local del repositorio:

1. Clona el repositorio desde GitHub.
2. Instala las herramientas de compilación.
3. Restaura `.tools/android.jar`.
4. Restaura `.keys/` desde la copia cifrada.
5. Ejecuta `./build.sh` o `bash build.sh`.
6. Verifica la firma y el SHA-256.

El código fuente está en GitHub; la clave de firma debe estar respaldada aparte.
Si pierdes también `.keys/`, podrás compilar una APK nueva, pero no actualizar
la app ya instalada con la misma identidad.

## Terceros

Incluye componentes de terceros, entre ellos `proot`, `tar`, `xz`, sus
librerías y xterm.js. Las licencias de xterm.js y su addon están en
`app/assets/js/`. Antes de publicar una release conviene añadir los avisos y
las licencias completas de los binarios de `app/assets/host/`.

## Licencia

Licencia pendiente de definir.

KalinDroid no es un proyecto oficial de Offensive Security, Kali Linux ni
NetHunter.
