#!/bin/bash
set -e
cd ~/android-build
export PATH=$HOME/android-build/jdk17/bin:$PATH
BT=sdk/build-tools/34.0.0
AJAR=sdk/platforms/android-34/android.jar
rm -rf out/flat out/gen out/classes out/dex
mkdir -p out/flat out/gen out/classes out/dex
$BT/aapt2 compile --dir app/res -o out/flat/res.zip
$BT/aapt2 link -o out/app-unsigned.apk --min-sdk-version 26 --target-sdk-version 34 -I $AJAR --manifest app/AndroidManifest.xml -A app/assets out/flat/res.zip --java out/gen
javac -encoding UTF-8 -source 8 -target 8 -cp $AJAR -d out/classes $(find out/gen app/src -name "*.java")
$BT/d8 --lib $AJAR --output out/dex $(find out/classes -name "*.class")
cd out/dex && zip -q ../app-unsigned.apk classes.dex && cd ../..
$BT/zipalign -f 4 out/app-unsigned.apk out/app-aligned.apk
$BT/apksigner sign --ks out/feihaflous.keystore --ks-key-alias feihaflous --ks-pass pass:KEYSTORE_PASS --key-pass pass:KEYSTORE_PASS --out out/feihaflous.apk out/app-aligned.apk
$BT/apksigner verify out/feihaflous.apk
$BT/aapt2 dump badging out/feihaflous.apk | head -4
