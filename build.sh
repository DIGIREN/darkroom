#!/bin/bash
# Build an unsigned-then-debug-signed APK with the raw SDK tools (no Gradle).
set -e
SDK=/sdk; BT=$SDK/build-tools/34.0.0; JAR=$SDK/platforms/android-34/android.jar
rm -rf out && mkdir -p out/res out/classes out/dex

$BT/aapt2 compile --dir res -o out/res.zip
$BT/aapt2 link -o out/base.apk -I $JAR --manifest AndroidManifest.xml \
    --java out/gen out/res.zip --auto-add-overlay
mkdir -p out/gen
javac -source 8 -target 8 -nowarn -bootclasspath $JAR -classpath $JAR \
    -d out/classes $(find src out/gen -name '*.java' 2>/dev/null)
$BT/d8 --lib $JAR --output out/dex $(find out/classes -name '*.class')

cd out && cp base.apk unsigned.apk && zip -qj unsigned.apk dex/classes.dex && cd ..

if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
      -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
      -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
$BT/zipalign -f 4 out/unsigned.apk out/aligned.apk
$BT/apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
    --out darkroom.apk out/aligned.apk
echo "BUILT: $(ls -lh darkroom.apk | awk '{print $5}')"
