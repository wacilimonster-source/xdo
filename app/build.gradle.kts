// X 下载助手 xdo · app 模块构建脚本
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.xdo.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xdo.app"
        minSdk = 26
        targetSdk = 34
        // 发布新版本时同步修改；version.txt 的 versionCode 必须 >= 此值（判更新唯一依据）
        versionCode = 22
        versionName = "0.2.11"
    }

    // 发版签名：必须继续使用 v0.2.8 及更早版本的历史密钥，否则老用户会因签名不同无法覆盖安装。
    // 密钥不入库，固定放在 ~/.android/xdo-release.keystore（alias=androiddebugkey，密码=android）。
    // 注意：v0.2.9/v0.2.10 误用了当前机器的新 debug 密钥，导致从 v0.2.8 升级失败；v0.2.11 起恢复。
    val stableKeystore = File(System.getProperty("user.home"), ".android/xdo-release.keystore")
    signingConfigs {
        if (stableKeystore.exists()) {
            create("releaseStable") {
                storeFile = stableKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            // 发布版使用与历史版本一致的密钥签名，保证可以覆盖安装
            signingConfig = if (stableKeystore.exists()) {
                signingConfigs.getByName("releaseStable")
            } else {
                // 兜底：没有历史密钥时退回 debug 签名（会与老版本不一致，仅用于本机构建）
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
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
    packagingOptions {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx) {
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

    debugImplementation(libs.androidx.ui.tooling)
}