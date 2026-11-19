#!/bin/bash
# Builds build/mousebridge.apk with the Android SDK command-line tools only (no Gradle needed).
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
AJ="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar"

rm -rf build
mkdir -p build/classes

"$BT/aapt2" link -o build/base.apk --manifest AndroidManifest.xml -I "$AJ" \
  --min-sdk-version 30 --target-sdk-version 34
javac -Xlint:-options --release 17 -cp "$AJ" -d build/classes $(find src -name '*.java')
"$BT/d8" --release --min-api 30 --lib "$AJ" --output build $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build && zip -q unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

KS="$HOME/.android/debug.keystore"
if [ ! -f "$KS" ]; then
  mkdir -p "$HOME/.android"
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out build/mousebridge.apk build/aligned.apk
echo "built $(pwd)/build/mousebridge.apk"
