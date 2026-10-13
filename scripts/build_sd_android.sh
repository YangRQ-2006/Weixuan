#!/usr/bin/env bash
# =============================================================================
# 微玄 · 本地图像生成引擎构建脚本（GPU / Vulkan 后端）
#
# 目标：把 stable-diffusion.cpp（MIT，leejet/stable-diffusion.cpp）连同
#       JNI 桥（app/src/main/jni/sd_jni.cpp）编译成
#       Android arm64-v8a 的共享库 libweixuan_sd.so
#
# 关键点：
#   · 用 Vulkan 后端（Adreno 840）—— OpenCL 在普通 Android App 里因
#     linker 命名空间限制访问不到 /vendor 的 libOpenCL.so
#   · 用 sd.cpp 自带的 patched ggml（子模块）—— 上游 ggml 缺 SD 算子
#   · JNI 桥作为 CMake 目标与 sd.cpp 一起构建 —— 静态库链接顺序
#     （stable-diffusion → ggml → ggml-vulkan → onig → utf8proc）
#     由 CMake 处理，手动 clang++ 极易排错
#   · glslc 用沙箱自带的 /usr/bin/glslc（着色器在宿主机编译，非设备端）
#
# 用法：sh scripts/build_sd_android.sh [--skip-clone]
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"          # /workspace/Eta
SRC_DIR="$ROOT/.sd-build/stable-diffusion.cpp"
BUILD_DIR="$ROOT/.sd-build/build-android-vk"
OUT_SO="$ROOT/app/src/main/jniLibs/arm64-v8a/libweixuan_sd.so"

SD_REPO="git@github.com:leejet/stable-diffusion.cpp.git"
SD_REF="master"

log() { printf '\n\033[1;36m[sd-build]\033[0m %s\n' "$*"; }
die() { printf '\n\033[1;31m[sd-build] 错误: %s\033[0m\n' "$*" >&2; exit 1; }

# ---- 1. 定位 NDK -----------------------------------------------------------
NDK="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK" ]; then
  SDK_DIR="$(grep -m1 '^sdk.dir=' "$ROOT/local.properties" 2>/dev/null | cut -d= -f2-)"
  [ -n "$SDK_DIR" ] || SDK_DIR="$HOME/Android/Sdk"
  for d in "$SDK_DIR"/ndk/*; do
    [ -d "$d" ] && NDK="$d" && break
  done
fi
[ -n "$NDK" ] && [ -f "$NDK/build/cmake/android.toolchain.cmake" ] \
  || die "找不到 Android NDK（需要 $SDK_DIR/ndk/<version>/build/cmake/android.toolchain.cmake）"
log "NDK: $NDK"

# ---- 2. 定位 glslc（着色器编译器，宿主机侧）-------------------------------
GLSLC="$(command -v glslc || true)"
[ -n "$GLSLC" ] || die "找不到 glslc（apt install glslc 或安装 Vulkan SDK）"
log "glslc: $GLSLC"

# ---- 3. 取源码（含 ggml 子模块）-------------------------------------------
if [ "${1:-}" != "--skip-clone" ] || [ ! -d "$SRC_DIR" ]; then
  log "克隆 stable-diffusion.cpp（含子模块 ggml）…"
  rm -rf "$SRC_DIR"
  git clone --depth 1 --recurse-submodules --shallow-submodules "$SD_REPO" "$SRC_DIR" \
    || die "git clone 失败（沙箱 SSH 可用；若失败请检查 ~/.ssh/id_ed25519_weixuan）"
  ( cd "$SRC_DIR" && git fetch --depth 1 origin "$SD_REF" && git checkout -q "$SD_REF" )
fi
[ -f "$SRC_DIR/ggml/CMakeLists.txt" ] || die "缺少 ggml 子模块（克隆时请加 --recurse-submodules）"
log "源码: $SRC_DIR"

# ---- 4. 生成 JNI 封装 CMake 工程 ------------------------------------------
JNI_CMAKE="$ROOT/.sd-build/jni/CMakeLists.txt"
mkdir -p "$ROOT/.sd-build/jni"
cat > "$JNI_CMAKE" <<CMAKE_EOF
cmake_minimum_required(VERSION 3.22)
project(weixuan_sd LANGUAGES C CXX)

# sd.cpp 整体作为子工程（其内部会构建 ggml / ggml-vulkan / onig / utf8proc）
add_subdirectory("$SRC_DIR" "\${CMAKE_CURRENT_BINARY_DIR}/sd")

add_library(weixuan_sd SHARED "$ROOT/app/src/main/jni/sd_jni.cpp")
target_include_directories(weixuan_sd PRIVATE "$SRC_DIR/include")
# CMake 会按依赖顺序排列：stable-diffusion → ggml → ggml-vulkan → onig → utf8proc
target_link_libraries(weixuan_sd PRIVATE stable-diffusion)

find_library(VULKAN_LIB vulkan REQUIRED)
find_library(LOG_LIB log REQUIRED)
find_library(ANDROID_LIB android REQUIRED)
find_library(JNIGRAPHICS_LIB jnigraphics REQUIRED)
target_link_libraries(weixuan_sd PRIVATE
  \${VULKAN_LIB} \${LOG_LIB} \${ANDROID_LIB} \${JNIGRAPHICS_LIB})
CMAKE_EOF
log "JNI CMake 工程: $JNI_CMAKE"

# ---- 5. CMake 配置 + 构建 -------------------------------------------------
log "CMake 配置（SD_VULKAN=ON, arm64-v8a）…"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"
cmake -S "$ROOT/.sd-build/jni" -B "$BUILD_DIR" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-28 \
  -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DGGML_OPENMP=OFF \
  -DGGML_VULKAN=ON \
  -DGGML_VULKAN_CHECK_RESULTS=OFF \
  -DGGML_VULKAN_RUN_TESTS=OFF \
  -DVulkan_GLSLC_EXECUTABLE="$GLSLC" \
  -DSD_VULKAN=ON \
  -DSD_BUILD_EXAMPLES=OFF \
  -DSD_WEBP=OFF \
  -DSD_WEBM=OFF \
  -DSD_USE_UPSTREAM_GGML=OFF \
  -DSPIRV-Headers_DIR=/usr/share/cmake/SPIRV-Headers \
  -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH \
  || die "CMake 配置失败"

log "构建 libweixuan_sd.so …"
cmake --build "$BUILD_DIR" --config Release --target weixuan_sd -j"$(nproc)" \
  || die "构建失败"

# ---- 6. 产物 ---------------------------------------------------------------
BUILT="$(find "$BUILD_DIR" -name 'libweixuan_sd.so' | head -1)"
[ -n "$BUILT" ] || die "未找到 libweixuan_sd.so"

mkdir -p "$(dirname "$OUT_SO")"
cp -f "$BUILT" "$OUT_SO"
log "✓ 产物: $OUT_SO ($(du -h "$OUT_SO" | cut -f1))"
log ""
log "下一步：把 SD1.5 的 .gguf 模型（约 2GB）放到"
log "  /data/data/cn.yangrq.weixuan/files/models/sd/"
log "然后在设置里开启「本地图像生成」即可"
