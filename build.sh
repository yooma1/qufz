#!/bin/sh
# 构建 AntiFraudBlock（libxposed API 102 / LSPosed modern module）
#
# 依赖：javac(JDK11+) / aapt2 / d8 / zipalign / apksigner / android.jar(platform 34)
# 首次构建会从 Maven Central 拉 libxposed api-102.0.0.aar（没有国内镜像，约 30KB）
#
# 用法：
#   ./build.sh                 开发构建，产物进 out/（不入库）
#   ./build.sh release         发布构建，产物直接进 release/（入库）
#
# 发布构建必须指到正式密钥，否则会用 debug 证书覆盖 release/ 里的正式包：
#   AFB_KS=~/afb.jks AFB_KS_PASS=xxx AFB_KS_ALIAS=afb AFB_KS_KEYPASS=xxx ./build.sh release
set -e
cd "$(dirname "$0")"

OUT_DIR=${1:-out}
VER=2.1

SDK=${ANDROID_SDK:-/opt/android-sdk}
BT=${ANDROID_BUILD_TOOLS:-$SDK/build-tools/36.0.0}
AJAR="$SDK/platforms/android-34/android.jar"
AAR=libs/libxposed-102.aar
XAPI=libs/aar/classes.jar

D8="$BT/d8"
AAPT2=aapt2
ZIPALIGN=zipalign
APKSIGNER=apksigner
CP_SEP=":"
if [ -f "$BT/d8.bat" ]; then
  D8="$BT/d8.bat"
  AAPT2="$BT/aapt2.exe"
  ZIPALIGN="$BT/zipalign.exe"
  APKSIGNER="$BT/apksigner.bat"
  CP_SEP=";"
fi

mkdir -p libs "$OUT_DIR"

# 1) 拉依赖
if [ ! -f "$XAPI" ]; then
  echo "[0/6] fetch libxposed api 102"
  [ -f "$AAR" ] || curl -sSL -o "$AAR" \
    https://repo1.maven.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar
  mkdir -p libs/aar && (cd libs/aar && unzip -o -q "../$(basename $AAR)")
fi

rm -rf build2
mkdir -p build2/classes

echo "[1/6] javac"
javac -encoding UTF-8 --release 11 -classpath "$AJAR$CP_SEP$XAPI" \
  -d build2/classes $(find module/src -name '*.java')

echo "[2/6] d8"
"$D8" --lib "$AJAR" --classpath "$XAPI" --min-api 33 \
  --output build2 $(find build2/classes -name '*.class')

echo "[3/6] aapt2"
"$AAPT2" compile --dir module/res -o build2/res.zip
"$AAPT2" link -o build2/base.apk -I "$AJAR" --manifest module/AndroidManifest.xml \
  -R build2/res.zip --min-sdk-version 33 --target-sdk-version 34 --auto-add-overlay

echo "[4/6] package"
rm -f build2/unsigned.apk
cp build2/base.apk build2/unsigned.apk
rm -rf build2/stage && mkdir -p build2/stage
cp build2/classes.dex build2/stage/classes.dex
cp -r module/META-INF build2/stage/
if command -v zip >/dev/null 2>&1; then
  (cd build2/stage && zip -q -r ../unsigned.apk classes.dex META-INF)
else
  # Git Bash on Windows may not ship zip; jar is already required by this build.
  (cd build2/stage && jar uf ../unsigned.apk classes.dex META-INF)
fi

echo "[5/6] zipalign"
"$ZIPALIGN" -f -p 4 build2/unsigned.apk build2/aligned.apk

echo "[6/6] sign"
# keystore 不入库：默认用 debug 证书，正式发布用环境变量覆盖
KS=${AFB_KS:-$HOME/.android/debug.keystore}
KS_PASS=${AFB_KS_PASS:-android}
KS_ALIAS=${AFB_KS_ALIAS:-androiddebugkey}
KS_KEYPASS=${AFB_KS_KEYPASS:-android}
if command -v cygpath >/dev/null 2>&1; then
  KS=$(cygpath -w "$KS")
fi

if [ "$OUT_DIR" = "release" ] && [ -z "$AFB_KS" ]; then
  echo "!! 发布构建没指定 AFB_KS，将用 debug 证书覆盖 release/ 里的正式包" >&2
  echo "!! 确认要这样就用 AFB_KS_ALLOW_DEBUG=1 ./build.sh release" >&2
  [ "${AFB_KS_ALLOW_DEBUG:-0}" = "1" ] || exit 1
fi

APK="$OUT_DIR/AntiFraudBlock-$VER.apk"
rm -f "$APK"
"$APKSIGNER" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_KEYPASS" \
  --ks-key-alias "$KS_ALIAS" --out "$APK" build2/aligned.apk

ls -la "$APK"
md5sum "$APK"
"$APKSIGNER" verify --print-certs "$APK" 2>/dev/null | head -2
echo "OK -> $APK"
