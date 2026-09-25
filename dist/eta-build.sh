#!/bin/sh
# ==============================================================================
# Eta 本地化（GenieX NPU）改造版 Android 构建脚本
# ------------------------------------------------------------------------------
# 适用依赖：AGP 9.3.2 / Kotlin 2.4.10 / KSP 2.3.9 / Gradle 9.6.1 /
#           compileSdk 37 / targetSdk 36 / NDK r29 / Java 25 toolchain
#
# 与太墟内置 builtin-android 的差异（本脚本存在的唯一理由）：
#   1) 官方引擎固定 GRADLE_HOME=/opt/gradle-8.14.2，无法驱动 AGP 9.x；
#      本脚本使用与项目 wrapper 一致的 Gradle 9.6.1（默认 /opt/gradle-9.6.1）；
#   2) 项目 build.gradle.kts 声明 Java 25 toolchain，本脚本允许 Gradle 通过
#      foojay-resolver 自动装配 aarch64 JDK（可用 TAIXU_JDK_PATH 指定本地 JDK）；
#   3) 保留官方策略：ARM64 aapt2 override、SDK 禁自动下载、单 daemon + 2 worker 低内存。
#
# 用法：eta-build.sh <项目目录> [Gradle任务，默认 assembleDebug]
# ==============================================================================
set -e

PROJECT_PATH="${1:-.}"
TASK="${2:-assembleDebug}"

echo "==> [Eta Build] 项目: $PROJECT_PATH"
echo "==> [Eta Build] 任务: $TASK"

if [ -f /etc/profile.d/taixu-android.sh ]; then . /etc/profile.d/taixu-android.sh; fi
# 注意：taixu-android.sh 会导出 GRADLE_HOME=/opt/gradle-8.14.2，必须在 source 之后再覆盖，
# 否则又会退回 8.14.2（无法驱动 AGP 9.x）。
GRADLE_HOME="${TAIXU_ETA_GRADLE_HOME:-/opt/gradle-9.6.1}"
export GRADLE_HOME
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/root/.gradle}"
export TAIXU_NDK_PATH="${TAIXU_NDK_PATH:-/opt/taixu/toolchains/android/ndk/current}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$TAIXU_NDK_PATH}"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
export PATH="$JAVA_HOME/bin:/opt/taixu/bin:$PATH"

if [ ! -x "$JAVA_HOME/bin/java" ]; then
    echo "==> [Eta Build] 缺少可执行 JDK: $JAVA_HOME"; exit 127
fi
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
    echo "==> [Eta Build] 缺少 Gradle: $GRADLE_HOME"; exit 127
fi
if [ ! -f "$TAIXU_NDK_PATH/source.properties" ]; then
    echo "==> [Eta Build] 固定 ARM64 NDK 未就位: $TAIXU_NDK_PATH"; exit 2
fi

LOCAL_PROPERTIES="$PROJECT_PATH/local.properties"
LOCAL_PROPERTIES_TMP="${LOCAL_PROPERTIES}.eta.tmp"
if [ -f "$LOCAL_PROPERTIES" ]; then
    sed -e '/^[[:space:]]*sdk\.dir[[:space:]]*=/d' "$LOCAL_PROPERTIES" > "$LOCAL_PROPERTIES_TMP"
else
    : > "$LOCAL_PROPERTIES_TMP"
fi
printf 'sdk.dir=%s\n' "$ANDROID_HOME" >> "$LOCAL_PROPERTIES_TMP"
mv -f "$LOCAL_PROPERTIES_TMP" "$LOCAL_PROPERTIES"
echo "==> [Eta Build] ANDROID_HOME: $ANDROID_HOME"

AAPT2_OVERRIDE="${TAIXU_AAPT2_PATH:-/opt/taixu/toolchains/android/sdk-tools/artifacts/db1cea2c4454d5f9c5a802646b2d1cf560b4ee7badbe23e51ab8e1881bb50fc2/build-tools/aapt2}"
if [ ! -x "$AAPT2_OVERRIDE" ]; then
    echo "==> [Eta Build] ARM64 AAPT2 未就位: $AAPT2_OVERRIDE"; exit 2
fi

SSL_OPTS=""
if [ -s /etc/ssl/certs/java/cacerts ]; then
    SSL_OPTS="-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=changeit"
fi
export GRADLE_OPTS="${GRADLE_OPTS:-} -Djava.security.egd=file:/dev/urandom $SSL_OPTS"

EXTRA_ARGS="--console=plain --info --stacktrace --no-daemon --max-workers=2 --no-configuration-cache"
EXTRA_ARGS="$EXTRA_ARGS -Pandroid.builder.sdkDownload=false"
EXTRA_ARGS="$EXTRA_ARGS -Pandroid.aapt2FromMavenOverride=$AAPT2_OVERRIDE"
EXTRA_ARGS="$EXTRA_ARGS -Dorg.gradle.internal.http.connectionTimeout=60000"
EXTRA_ARGS="$EXTRA_ARGS -Dorg.gradle.internal.http.socketTimeout=180000"
INIT_GRADLE="${TAIXU_ETA_INIT_GRADLE:-/workspace/NPULlmChat/dist/eta-mirrors.gradle}"
if [ -f "$INIT_GRADLE" ]; then
    EXTRA_ARGS="$EXTRA_ARGS -I $INIT_GRADLE"
    echo "==> [Eta Build] 仓库收敛脚本: $INIT_GRADLE"
fi
if [ -n "${TAIXU_JDK_PATH:-}" ]; then
    EXTRA_ARGS="$EXTRA_ARGS -Dorg.gradle.java.installations.paths=$TAIXU_JDK_PATH"
fi

echo "==> [Eta Build] Gradle: $GRADLE_HOME"
echo "==> [Eta Build] JDK(launcher): $JAVA_HOME"
echo "==> [Eta Build] NDK: $TAIXU_NDK_PATH"
echo "==> [Eta Build] 开始构建（AGP/androidx 走阿里云镜像，首次需下载依赖）..."

cd "$PROJECT_PATH"
# 内存：用户级 /root/.gradle/gradle.properties 把 kotlin.daemon.jvmargs 限成 512m；
# 而本机 Android 侧占用较大（MemAvailable 仅 ~3~4GB），Gradle daemon + Kotlin daemon
# 两个 JVM 叠加会触发 swap 抖动，实测报 "GC overhead limit exceeded"。
# 因此改为**单 JVM**：Kotlin 编译在 Gradle daemon 内进程内执行（in-process），
# 只保留一个 4GB 堆，既够 KSP+Compose 编译 417 个文件，又不至于把设备压进 swap 死循环。
exec "$GRADLE_HOME/bin/gradle" $TASK $EXTRA_ARGS \
    "-Dorg.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=1024m -XX:+UseParallelGC -Dfile.encoding=UTF-8" \
    "-Pkotlin.compiler.execution.strategy=in-process"
