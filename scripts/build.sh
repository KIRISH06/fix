#!/usr/bin/env bash
# Builds OfflinePay-fixed.apk from base/app-debug.apk + patch/.
# Needs: java 17+, python3, zip/unzip, Android SDK (build-tools + a platform).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/build"
OUT="$ROOT/out"
APKTOOL_VERSION=2.11.1

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
BT="$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)"; BT="${BT%/}"
if [ -d "$SDK/platforms/android-34" ]; then PLAT="$SDK/platforms/android-34"; else PLAT="$(ls -d "$SDK"/platforms/android-*/ | sort -V | tail -1)"; PLAT="${PLAT%/}"; fi
echo "build-tools: $BT"; echo "platform:    $PLAT"

rm -rf "$WORK" "$OUT"; mkdir -p "$WORK" "$OUT"

# 1. apktool
APKTOOL="$WORK/apktool.jar"
curl -fsSL -o "$APKTOOL" "https://github.com/iBotPeaches/Apktool/releases/download/v${APKTOOL_VERSION}/apktool_${APKTOOL_VERSION}.jar"

# 2. decode the base APK and apply the manifest / resource / smali patch
java -jar "$APKTOOL" d -f -o "$WORK/dec" "$ROOT/base/app-debug.apk"
python3 "$ROOT/scripts/apply_patch.py" "$WORK/dec" "$ROOT/patch"

# 3. compile the new Java classes (accessibility service + USSD bridge) to a dex
mkdir -p "$WORK/stubcls" "$WORK/cls" "$WORK/dexout"
javac --release 8 -Xlint:-options -d "$WORK/stubcls" "$ROOT/patch/stubs/kotlin/jvm/functions/Function1.java"
javac --release 8 -Xlint:-options -cp "$PLAT/android.jar:$WORK/stubcls" -d "$WORK/cls" "$ROOT"/patch/java/com/offlinepay/fix/*.java
( cd "$WORK" && jar cf stubs.jar -C stubcls . )
"$BT/d8" --min-api 26 --lib "$PLAT/android.jar" --classpath "$WORK/stubs.jar" \
         --output "$WORK/dexout" $(find "$WORK/cls/com" -name '*.class')

# 4. rebuild the APK from the patched project
java -jar "$APKTOOL" b -o "$WORK/unsigned.apk" "$WORK/dec"

# 5. add the new dex as the next classesN.dex
N=$(unzip -Z1 "$WORK/unsigned.apk" | grep -Ec '^classes[0-9]*\.dex$')
NEXT="classes$((N+1)).dex"
cp "$WORK/dexout/classes.dex" "$WORK/dexout/$NEXT"
( cd "$WORK/dexout" && zip -q "$WORK/unsigned.apk" "$NEXT" )
echo "added $NEXT"

# 6. align + sign (fixed keystore => every build is signed with the same key, updates install over each other)
KS="$ROOT/patch/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias offlinepay \
          -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=OfflinePay, O=OfflinePay, C=IN"
fi
"$BT/zipalign" -f -p 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android --ks-key-alias offlinepay \
                --out "$OUT/OfflinePay-fixed.apk" "$WORK/aligned.apk"
"$BT/apksigner" verify --verbose "$OUT/OfflinePay-fixed.apk" | head -5

# 7. sanity checks: the patched pieces must really be inside the APK
unzip -p "$OUT/OfflinePay-fixed.apk" AndroidManifest.xml | strings -el | grep -q "UssdAccessibilityService" \
  || { echo "ERROR: service missing from manifest"; exit 1; }
unzip -p "$OUT/OfflinePay-fixed.apk" "$NEXT" | grep -aq "Lcom/offlinepay/fix/UssdBridge;" \
  || { echo "ERROR: UssdBridge missing from $NEXT"; exit 1; }
ls -la "$OUT"
echo "DONE -> $OUT/OfflinePay-fixed.apk"
