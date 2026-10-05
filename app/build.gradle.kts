import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ── release 签名配置 ────────────────────────────────────────────────────────
// 取值优先级：**环境变量 > keystore.properties > 无**（无签名时 release 构建会拒绝出包，
// 这是刻意的：宁可不出包，也不要产出一个用调试密钥签名的"发布包"）。
//
// keystore.properties 默认放在**仓库之外**：<仓库父目录>/weixuan-keystore/keystore.properties
// （可用环境变量 WEIXUAN_KEYSTORE_PROPERTIES 指向别处）。
// 内容四项：storeFile / storePassword / keyAlias / keyPassword。
// 该文件与 *.jks 均已被 .gitignore 挡住，不会进版本库。
val keystorePropsFile: File = System.getenv("WEIXUAN_KEYSTORE_PROPERTIES")
    ?.takeIf { it.isNotBlank() }
    ?.let { file(it) }
    ?: File(rootProject.projectDir.parentFile, "weixuan-keystore/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

fun signingValue(envKey: String, propKey: String): String? {
    val fromEnv: String? = System.getenv(envKey)
    val raw: String? = if (fromEnv != null && fromEnv.isNotBlank()) {
        fromEnv
    } else {
        keystoreProps.getProperty(propKey)
    }
    // 占位符不算配置值：否则「模板还没填」会被误判成「已配置」，
    // 守卫失效、构建跑到签名阶段才炸，报错还很难懂。
    if (raw.isNullOrBlank() || raw.contains("REPLACE_ME")) return null
    return raw
}

val releaseStoreFile = signingValue("WEIXUAN_RELEASE_STORE_FILE", "storeFile")?.let {
    // 允许 properties 里写相对路径（相对 keystore.properties 所在目录）
    val f = File(it)
    if (f.isAbsolute) f.absolutePath else File(keystorePropsFile.parentFile, it).absolutePath
}
val releaseStorePassword = signingValue("WEIXUAN_RELEASE_STORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("WEIXUAN_RELEASE_KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("WEIXUAN_RELEASE_KEY_PASSWORD", "keyPassword")
val hasReleaseSigning = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() } &&
    // 光有配置不算：密钥库文件必须真实存在，否则签名必然失败。
    releaseStoreFile?.let { File(it).isFile } == true

// 本地发布前自检：若确实要出 release 包却没配签名，直接报错并给出该怎么做。
val wantsReleaseBuild = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
if (wantsReleaseBuild && !hasReleaseSigning) {
    throw GradleException(
        """
        |未找到 release 签名配置，拒绝出包（避免产出用调试密钥签名的"发布包"）。
        |
        |请二选一：
        |  A) 填写 ${keystorePropsFile.absolutePath}
        |     内容示例（storeFile 可用相对路径，相对该 properties 文件所在目录）：
        |       storeFile=weixuan-release.jks
        |       storePassword=你的密钥库口令
        |       keyAlias=weixuan
        |       keyPassword=你的密钥口令
        |  B) 或用环境变量 WEIXUAN_RELEASE_STORE_FILE / _STORE_PASSWORD / _KEY_ALIAS / _KEY_PASSWORD
        """.trimMargin()
    )
}

java {
    toolchain {
        // 排查：原为 25（Gradle 9.6.1 官方支持到 24）。release 独有的 R8 + 打包链在
        // 不受支持的 JDK 上卡死/产出异常 APK，故改用 AGP 9 官方支持的 21 验证。
        languageVersion = JavaLanguageVersion.of(21)
    }
}

android {
    namespace = "cn.yangrq.weixuan"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "cn.yangrq.weixuan"
        minSdk = 34
        targetSdk = 36
        // 首次开源发布：versionName 0.0.1，versionCode 从 1 起算。
        // （此前内部迭代用 yyyyMMdd+序号 的编码，开源后改为语义化版本；
        //   versionCode 会低于此前内部构建，覆盖安装需允许降级或先卸载）
        // 0.0.2：版本号 +1（versionCode 2），本轮主题为「本地推理服务器模式」系列功能
        // （OpenAI 兼容接口 / 独立二级设置页 / 并发槽位 / 热断路器 / 保活加固）。
        //   注意：沿用 1 起算的 versionCode 会低于早期内部（yyyyMMdd 编码）构建，
        //   覆盖安装仍需允许降级或先卸载，此处刻意保持不变，避免与已发布 0.0.1 语义冲突。
        versionCode = 2
        versionName = "0.0.2"

        // GenieX 本地推理 SDK 仅提供 arm64-v8a 原生库，同时收敛 APK 体积。
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isPseudoLocalesEnabled = true
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    buildFeatures {
        buildConfig = false
        compose = true
    }

    androidResources {
        localeFilters += listOf("en", "b+zh+Hans", "b+zh+Hant")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += setOf("**/libproot_exec.so", "**/libproot_loader.so", "**/libeta_pty.so")
        }
        resources {
            // 合并 Xposed 模块声明，避免 release 裁剪后模块入口失效
            merges += "META-INF/xposed/*"
            // 仅排除会引发打包冲突的签名/版本元数据，避免误伤 Compose 资源
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.commons.compress)
    implementation(libs.xz)
    compileOnly(libs.libxposed.api)
    // UI 侧 RemotePreferences 写入桥：通过 XposedService 将配置提交到 LSPosed 数据库；
    // Hook 侧用 XposedInterface.getRemotePreferences 读取当前进程持有的配置缓存。
    implementation(libs.libxposed.service)
    implementation(libs.dexkit)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.nav)
    implementation(libs.miuix.preference)
    implementation(libs.androidx.navigationevent)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)
    // markdown-renderer-m3 将 material3 作为 compileOnly，需显式引入以满足运行时依赖
    implementation(libs.material3)
    implementation(libs.hidden.api.bypass)

    // GenieX 本地推理 SDK（高通 NPU/GPU，含 libQnnHtp* 与 libgeniex-proc.so，仅 arm64-v8a）
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // DataStore：Provider / Model 结构化 JSON 与当前选中 ID 等键值
    implementation(libs.datastore.preferences)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // OkHttp：替代 HttpURLConnection，支持 SSE
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    // Kotlinx Serialization：Provider 设置与运行时配置 JSON
    implementation(libs.kotlinx.serialization.json)

    // Coroutines：显式引入，避免依赖传递版本不确定
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
}
