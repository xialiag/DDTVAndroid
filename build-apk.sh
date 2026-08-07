#!/bin/bash
# ============================================================
# DDTV Android APK 构建脚本（参考 BBDownAndroid/build-apk.sh）
# 用法：
#   ./build-apk.sh            # release, FFmpeg 9（默认）
#   ./build-apk.sh debug      # debug, FFmpeg 9
#   ./build-apk.sh 6 release  # release, FFmpeg 6
#   ./build-apk.sh 8 release  # release, FFmpeg 8
#   ./build-apk.sh all release# release, FFmpeg 6 + 8 + 9 三版本
#
# 自动处理：
#   1. AAR 路径修复：反斜杠 zip 条目(jni\arm64-v8a\) → 正斜杠
#   2. 产物自检：native 库数量 + apksigner 签名验证
# ============================================================
set -e

FF_VER="${1:-9}"
case "$FF_VER" in
  6|8|9) BUILD_TYPE="${2:-release}" ;;
  all) BUILD_TYPE="${2:-release}" ;;
  debug|release) BUILD_TYPE="$FF_VER"; FF_VER="9" ;;
  *) echo "用法：$0 [6|8|9|all] [debug|release]"; exit 1 ;;
esac
case "$BUILD_TYPE" in
  debug|release) ;;
  *) echo "用法：$0 [6|8|9|all] [debug|release]"; exit 1 ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}" )" && pwd)"
APP_DIR="${SCRIPT_DIR}/app"
LIBS_DIR="${APP_DIR}/libs"
DIST_DIR="${SCRIPT_DIR}/dist"
OUTPUT_DIR="${APP_DIR}/build/outputs/apk/${BUILD_TYPE}"
AAR="${LIBS_DIR}/ffmpeg-kit-full-v9.aar"

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) ENV_NAME="windows" ;;
  Linux*) ENV_NAME="linux" ;;
  *) ENV_NAME="unknown" ;;
esac

echo "============================================================"
echo " DDTV Android APK 构建 (${ENV_NAME}/$(uname -m))"
echo " 构建类型: ${BUILD_TYPE}"
echo "============================================================"

# local.properties 检查
if [ -f local.properties ]; then
  SDK_DIR="$(grep '^sdk.dir=' local.properties | cut -d= -f2)"
  if echo "$SDK_DIR" | grep -q '^C:'; then
    echo "✗ local.properties 指向 Windows 路径($SDK_DIR)，请改为 sdk.dir=/opt/android-sdk"
    exit 1
  fi
fi

# AAR 检查与修复
if [ ! -f "$AAR" ]; then
  echo "✗ 未找到 $AAR"
  exit 1
fi
PYTHON_BIN="$(command -v python3 || command -v python || true)"
if [ -n "$PYTHON_BIN" ]; then
  "$PYTHON_BIN" - "$AAR" <<'PYEOF'
import sys, zipfile, os
src = sys.argv[1]
try:
    zin = zipfile.ZipFile(src)
except Exception as e:
    print(f"  ⚠ 无法读取 AAR: {e}")
    sys.exit(0)
bad = [i for i in zin.infolist() if '\\' in i.filename]
if not bad:
    print("  AAR 路径正常(正斜杠)")
    sys.exit(0)
tmp = src + '.tmp'
with zipfile.ZipFile(src) as zin2, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin2.infolist():
        name = item.filename.replace('\\', '/')
        zi = zipfile.ZipInfo(name, item.date_time)
        zi.compress_type = zipfile.ZIP_DEFLATED
        zout.writestr(zi, zin2.read(item.filename))
os.replace(tmp, src)
print(f"  ✓ 已修复 {len(bad)} 个反斜杠路径条目")
PYEOF
fi

# 构建
FFMPEG_ARGS=()
if [ "$FF_VER" != "all" ]; then
  FFMPEG_ARGS=("-PffmpegVersion=$FF_VER")
fi
if [ "$ENV_NAME" = "windows" ]; then
  ./gradlew.bat "assemble${BUILD_TYPE^}" --console=plain "${FFMPEG_ARGS[@]}"
else
  ./gradlew "assemble${BUILD_TYPE^}" --console=plain "${FFMPEG_ARGS[@]}"
fi

VERSION_NAME="$(grep 'versionName' "${APP_DIR}/build.gradle" | sed 's/.*"\(.*\)".*/\1/' | head -1)"
APK="${OUTPUT_DIR}/app-${BUILD_TYPE}.apk"
if [ ! -f "$APK" ]; then
  echo "✗ 构建成功但未找到产物 $APK"
  exit 1
fi
mkdir -p "$DIST_DIR"
if [ "$FF_VER" = "all" ]; then
  # all 模式:构建 FFmpeg 6、8、9 三个版本
  for v in 6 8 9; do
    ./gradlew "assemble${BUILD_TYPE^}" --console=plain "-PffmpegVersion=$v"
    cp "$APK" "${DIST_DIR}/DDTV-${VERSION_NAME}-ffmpeg-${v}.${BUILD_TYPE}.apk"
  done
  OUT_NAME=""
else
  OUT_NAME="DDTV-${VERSION_NAME}-ffmpeg-${FF_VER}.${BUILD_TYPE}.apk"
fi
if [ -n "$OUT_NAME" ]; then
  cp "$APK" "${DIST_DIR}/${OUT_NAME}"
  echo "✓ 产物: dist/${OUT_NAME} ($(du -h "${DIST_DIR}/${OUT_NAME}" | cut -f1))"
else
  echo "✓ 产物: dist/DDTV-${VERSION_NAME}-ffmpeg-6/8.${BUILD_TYPE}.apk"
fi

# native 库自检
if [ -n "$PYTHON_BIN" ]; then
  SO_COUNT="$("$PYTHON_BIN" -c "
import zipfile, sys
z = zipfile.ZipFile('$APK')
print(sum(1 for i in z.infolist() if i.filename.startswith('lib/arm64-v8a/') and i.filename.endswith('.so')))
")"
  if [ "$SO_COUNT" -eq 0 ]; then
    echo "✗ APK 内未发现 native 库！"
    exit 1
  fi
  echo "✓ native 库: ${SO_COUNT} 个 (arm64-v8a)"
fi

# 签名自检
APKSIGNER=""
if [ "$ENV_NAME" = "windows" ]; then
  [ -f "${SDK_DIR}/build-tools/34.0.0/apksigner.bat" ] && APKSIGNER="${SDK_DIR}/build-tools/34.0.0/apksigner.bat"
else
  [ -f "${SDK_DIR}/build-tools/34.0.0/apksigner" ] && APKSIGNER="${SDK_DIR}/build-tools/34.0.0/apksigner"
fi
if [ -n "$APKSIGNER" ] && "$APKSIGNER" verify --min-sdk-version 21 "$APK" >/dev/null 2>&1; then
  echo "✓ 签名验证: OK (v1+v2)"
fi
echo "============================================================"
echo " 构建完成: ${DIST_DIR}/${OUT_NAME}"
echo "============================================================"
