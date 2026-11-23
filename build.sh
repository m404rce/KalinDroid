#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
BUILD="$ROOT/build"
ANDROID_JAR="$ROOT/.tools/android.jar"
KEYDIR="$ROOT/.keys"
KEYSTORE="$KEYDIR/kalindroid.jks"
PASSFILE="$KEYDIR/password"
ALIAS="kalindroid"
OUT="$BUILD/KalinDroid-1.3.apk"

for tool in aapt2 d8 apksigner keytool javac clang zip; do
    command -v "$tool" >/dev/null || { echo "Falta $tool"; exit 1; }
done
[ -f "$ANDROID_JAR" ] || { echo "Falta $ANDROID_JAR"; exit 1; }

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/apk/lib/arm64-v8a" "$KEYDIR"
chmod 700 "$KEYDIR"

if [ ! -f "$KEYSTORE" ]; then
    PASS="$(head -c 32 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 24)"
    printf '%s\n' "$PASS" > "$PASSFILE"
    chmod 600 "$PASSFILE"
    keytool -genkeypair \
        -keystore "$KEYSTORE" \
        -storetype PKCS12 \
        -alias "$ALIAS" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -storepass "$PASS" \
        -keypass "$PASS" \
        -dname "CN=KalinDroid, OU=Mobile, O=KalinDroid, C=ES"
    chmod 600 "$KEYSTORE"
fi

PASS="$(cat "$PASSFILE")"

JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
echo "[0/6] Nativo"
clang --target=aarch64-linux-android24 -shared -fPIC -O2 \
    -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
    -o "$ROOT/app/libs/arm64-v8a/libpty.so" \
    "$ROOT/app/jni/pty.c" -llog -ldl -pthread

echo "[1/6] Recursos"
aapt2 compile --dir "$ROOT/app/res" -o "$BUILD/res.zip"

echo "[2/6] Enlazando APK"
aapt2 link \
    -o "$BUILD/unsigned.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$ROOT/app/AndroidManifest.xml" \
    -A "$ROOT/app/assets" \
    --java "$BUILD/gen" \
    --min-sdk-version 26 \
    --target-sdk-version 28 \
    --version-code 4 \
    --version-name 1.3 \
    "$BUILD/res.zip"

echo "[3/6] Java"
mapfile -t JAVA_SOURCES < <(find "$ROOT/app/java" "$BUILD/gen" -name '*.java' -print)
javac -encoding UTF-8 -source 8 -target 8 \
    -classpath "$ANDROID_JAR" \
    -d "$BUILD/classes" \
    "${JAVA_SOURCES[@]}"

echo "[4/6] DEX"
mapfile -t CLASS_FILES < <(find "$BUILD/classes" -name '*.class' -print)
d8 --min-api 26 --lib "$ANDROID_JAR" --output "$BUILD/dex" "${CLASS_FILES[@]}"

echo "[5/6] Empaquetando"
cp "$BUILD/unsigned.apk" "$BUILD/unaligned.apk"
cp "$BUILD/dex/classes.dex" "$BUILD/apk/classes.dex"
cp "$ROOT/app/libs/arm64-v8a/libpty.so" "$BUILD/apk/lib/arm64-v8a/libpty.so"
(cd "$BUILD/apk" && zip -q -r "$BUILD/unaligned.apk" classes.dex lib)

echo "[6/6] Firmando"
apksigner sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$ALIAS" \
    --ks-pass "pass:$PASS" \
    --key-pass "pass:$PASS" \
    --min-sdk-version 26 \
    --out "$OUT" \
    "$BUILD/unaligned.apk"

apksigner verify --verbose "$OUT" | sed -n '1,8p'
ls -lh "$OUT"
