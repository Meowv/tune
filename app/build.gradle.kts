import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// 签名材料只从本地 keystore.properties 读取，文件不存在时不配置签名。
val releaseKeystore = rootProject.file("keystore.properties")
val releaseProperties = Properties().apply {
    if (releaseKeystore.exists()) releaseKeystore.inputStream().use { load(it) }
}

android {
    namespace = "com.meowv.tune"
    compileSdk = 35
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.meowv.tune"
        minSdk = 28
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.0"
        testInstrumentationRunner = "com.meowv.tune.TuneTestRunner"

        // 版本清单地址与发布渠道属于客户端配置，换渠道/换域名只需改这两行。
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"https://studio.meowv.com/data/meowvtune/stable.json\"")
        buildConfigField("String", "UPDATE_CHANNEL", "\"stable\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 签名材料只从本地 keystore.properties 读取，文件不存在时不配置签名，
    // assembleRelease 产出未签名包，密钥与口令不进入仓库或构建日志。
    signingConfigs {
        if (releaseKeystore.exists()) {
            create("release") {
                storeFile = rootProject.file(releaseProperties.getProperty("storeFile"))
                storePassword = releaseProperties.getProperty("storePassword")
                keyAlias = releaseProperties.getProperty("keyAlias")
                keyPassword = releaseProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        abortOnError = true
        htmlReport = true
        xmlReport = true
    }
}

dependencies {
    implementation(libs.core.splashscreen)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.coroutines.android)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.dnsjava)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.media3.session)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.hls)
    implementation(libs.media3.okhttp)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
