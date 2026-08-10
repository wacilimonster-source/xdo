// X 下载助手 xdo · app 模块构建脚本
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
        versionCode = 10
        versionName = "0.1.9"
    }

    buildTypes {
        release {
            // 发布版使用 debug 签名，方便用户直接安装测试
            signingConfig = signingConfigs.getByName("debug")
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

    debugImplementation(libs.androidx.ui.tooling)
}