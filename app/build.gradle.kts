// X 下载助手 xdo · app 模块构建脚本
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// ============================================================
// 版本号：唯一来源是仓库根目录 version.txt
// 本地构建与 GitHub Actions 读同一份数据，发版只需改 version.txt 一处。
// 临时覆盖：./gradlew -PxdoVersionCode=30 -PxdoVersionName=0.3.0 ...
// ============================================================
val versionFile = rootProject.file("version.txt")
val versionText = if (versionFile.isFile) versionFile.readText(Charsets.UTF_8) else ""

fun versionString(name: String): String? =
    Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(versionText)?.groupValues?.get(1)

fun versionInt(name: String): Int? =
    Regex("\"$name\"\\s*:\\s*(\\d+)").find(versionText)?.groupValues?.get(1)?.toIntOrNull()

val xdoVersionCode = (project.findProperty("xdoVersionCode") as String?)?.toIntOrNull()
    ?: versionInt("versionCode")
    ?: 22
val xdoVersionName = (project.findProperty("xdoVersionName") as String?)
    ?: versionString("versionName")
    ?: "0.2.11"

// ============================================================
// 发版签名：必须继续使用 v0.2.8 及更早版本的历史密钥，否则老用户会因签名不同无法覆盖安装。
// - 本地：~/.android/xdo-release.keystore
// - CI：XDO_KEYSTORE_FILE 指向由 Secrets（base64）解码出的密钥文件
// 口令/别名可用环境变量覆盖，默认值即历史密钥的配置。
// ============================================================
fun envOr(name: String, fallback: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() } ?: fallback

val stableKeystore = File(
    System.getenv("XDO_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
        ?: File(System.getProperty("user.home"), ".android/xdo-release.keystore").absolutePath
)
val hasStableKeystore = stableKeystore.isFile

if (!hasStableKeystore) {
    logger.warn(
        "xdo: 未找到发版密钥 ${stableKeystore.absolutePath}，" +
            "release 将退回 debug 签名（只可用于验证构建，不能对外发布）"
    )
}

android {
    namespace = "com.xdo.app"
    // T-01：34 → 36（Android 16）。targetSdk 36 的行为变化：
    // - Android 15：dataSync 前台服务每 24h 后台最多 6h，超时回调 onTimeout → 见 DownloadService
    // - Android 15/16：强制全面屏、不可再关闭 → MainActivity 早已 enableEdgeToEdge()，无影响
    // - Android 16：预测性返回默认开启 → 项目未重写 onBackPressed，返回由 Navigation 处理，无影响
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xdo.app"
        minSdk = 26
        targetSdk = 36
        // 来自仓库根 version.txt（version.txt 的 versionCode 即「判更新」的唯一依据）
        versionCode = xdoVersionCode
        versionName = xdoVersionName
        // D-01：数据库迁移的仪器测试（app/src/androidTest）
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasStableKeystore) {
            create("releaseStable") {
                storeFile = stableKeystore
                storePassword = envOr("XDO_STORE_PASSWORD", "android")
                keyAlias = envOr("XDO_KEY_ALIAS", "androiddebugkey")
                keyPassword = envOr("XDO_KEY_PASSWORD", "android")
            }
        }
    }

    buildTypes {
        release {
            // 发布版使用与历史版本一致的密钥签名，保证可以覆盖安装
            signingConfig = if (hasStableKeystore) {
                signingConfigs.getByName("releaseStable")
            } else {
                // 兜底：没有历史密钥时退回 debug 签名（会与老版本不一致，仅用于验证构建）
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // P-06：Compose 强跳过模式（Compose Compiler 1.5.4+ 支持，2.0 起默认开启）。
        // 捕获 ViewModel 等不稳定对象的 lambda 会被自动记忆，参数没变的卡片不再跟着整页重组。
        freeCompilerArgs += listOf(
            "-P",
            "plugin:androidx.compose.compiler.plugins.kotlin:experimentalStrongSkipping=true",
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
    // AGP 8 起 packagingOptions 已废弃，改用 packaging（同一配置）
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// D-01：Room 把每个数据库版本的表结构导出到 app/schemas/<版本>.json。
// 这些文件要提交到 git：以后的 AutoMigration 和迁移测试都依赖历史版本的 schema。
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

/** 打印实际参与构建的版本/签名信息，CI 日志里可直接核对 */
tasks.register("printBuildInfo") {
    group = "help"
    description = "打印实际参与构建的 versionCode / versionName 与签名密钥"
    doLast {
        println("versionCode = $xdoVersionCode")
        println("versionName = $xdoVersionName")
        println("keystore    = ${stableKeystore.absolutePath} (exists=$hasStableKeystore)")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx) {
        version { strictly(libs.versions.lifecycle.get()) }
    }
    // P-06：collectAsStateWithLifecycle（后台时停止收集）
    implementation(libs.androidx.lifecycle.runtime.compose) {
        version { strictly(libs.versions.lifecycle.get()) }
    }
    implementation(libs.androidx.lifecycle.viewmodel.compose) {
        version { strictly(libs.versions.lifecycle.get()) }
    }
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Room 数据层
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 网络（Syndication 解析 / version.txt 更新检查 / 下载）
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    // 媒体播放
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    // 图片加载
    implementation(libs.coil.compose)

    // 单元测试（B36：TweetLink / 播放列表解析 / 文件名等纯逻辑覆盖）
    testImplementation("junit:junit:4.13.2")

    // D-01：仪器测试（数据库迁移需要真实 SQLite，跑在设备/模拟器上）
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:core-ktx:1.5.0")

    debugImplementation(libs.androidx.ui.tooling)
}
