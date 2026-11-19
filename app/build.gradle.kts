plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.jingcun.cleaner"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jingcun.cleaner"
        minSdk = 31
        targetSdk = 36
        versionCode = 20
        versionName = "3.5.3"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/release.jks")
            storePassword = "jingcun123"
            keyAlias = "jingcun"
            keyPassword = "jingcun123"
        }
    }

    buildTypes {
        release {
            // v3.5.2：开启 R8 混淆收缩 + 资源收缩。本项目类已由 proguard-rules.pro
            // 全量 keep（行为不变），R8 仅裁剪第三方未用代码——APK 从 49MB 降至
            // 约 20MB，v3.2 老客户端 OTA 下载 46MB 大包在国内网络下断流/篡改导致
            // sha256 校验必败的问题随体积减半而大幅缓解。
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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
        // Shizuku 用户服务需要自定义 AIDL 接口
        aidl = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.material3.adaptive.navigation.suite)
    implementation(libs.androidx.material3.windowsize)
    // Shizuku：ADB 权限提取通道（GitHub: RikkaApps/Shizuku）
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    debugImplementation(libs.androidx.ui.tooling)
}
