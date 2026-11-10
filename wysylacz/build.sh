#!/usr/bin/env bash
# Buduje wysylacz.apk bez Gradle. Uzycie: bash build.sh
# Wymaga Android SDK (build-tools 35, platforma 35) i JDK 17+.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
BT="$SDK/build-tools/35.0.0"
JAR="$SDK/platforms/android-35/android.jar"
VERSION_CODE=3
VERSION_NAME=1.2

rm -rf build && mkdir -p build/classes build/dex

"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml -o build/base.apk \
    --min-sdk-version 26 --target-sdk-version 34 --version-code $VERSION_CODE --version-name $VERSION_NAME
javac -encoding UTF-8 -source 11 -target 11 -Xlint:-options -classpath "$JAR" -d build/classes \
    src/com/korzen/wysylacz/MainActivity.java
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --lib "$JAR" --min-api 26 \
    --output build/dex $(find build/classes -name '*.class')
py -c "import zipfile;z=zipfile.ZipFile('build/base.apk','a',zipfile.ZIP_DEFLATED);z.write('build/dex/classes.dex','classes.dex');z.close()"
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk

# Klucz do podpisu: generowany raz, NIE trafia do repozytorium. Ten sam klucz = aktualizacje sie instaluja.
[ -f sign.keystore ] || keytool -genkeypair -keystore sign.keystore -storepass android -keypass android \
    -alias wysylacz -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=wysylacz" >/dev/null 2>&1
java -jar "$BT/lib/apksigner.jar" sign --ks sign.keystore --ks-pass pass:android --key-pass pass:android \
    --out build/wysylacz.apk build/aligned.apk
java -jar "$BT/lib/apksigner.jar" verify build/wysylacz.apk && echo "OK build/wysylacz.apk"
ls -la build/wysylacz.apk
