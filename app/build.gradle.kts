plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名信息从环境变量读取（CI 里由加密变量注入）。密钥文件不入库。
//
// 变量名刻意加 signing 前缀：签名配置块内的属性与之同名，若直接叫
// storePassword / keyAlias / keyPassword，块内引用会解析到配置自身的属性，
// 变成"把自己赋给自己"，值实际为空。加前缀从根上避免这种冲突。
val signingKeystorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")
val signingStorePassword: String? = System.getenv("SIGNING_STORE_PASSWORD")
val signingKeyAlias: String? = System.getenv("SIGNING_KEY_ALIAS")
val signingKeyPassword: String? = System.getenv("SIGNING_KEY_PASSWORD")

val hasSigningConfig = !signingKeystorePath.isNullOrBlank() &&
    !signingStorePassword.isNullOrBlank() &&
    !signingKeyAlias.isNullOrBlank() &&
    !signingKeyPassword.isNullOrBlank()

android {
    namespace = "com.lmq.miccontrol"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lmq.miccontrol"
        minSdk = 29          // Android 10 起才有 AudioPlaybackCapture，这是功能下限
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    if (hasSigningConfig) {
        signingConfigs {
            create("release") {
                storeFile = file(signingKeystorePath!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 有签名配置就用正式签名，没有则退回 debug 签名，保证 CI 在未配置密钥时也能出包
            signingConfig = if (hasSigningConfig) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        // 注意：debug 不追加包名后缀。Xposed 模块按包名读取配置、LSPosed 也按包名
        // 设置作用域，包名一旦随构建类型变化就会导致模块读不到开关状态。
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/xposed/*")
        }
    }
}

dependencies {
    // Xposed 传统 API：只参与编译，运行时由 LSPosed 框架提供，绝不能打进 APK
    compileOnly("de.robv.android.xposed:api:82")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.activity:activity-compose:1.11.0")

    val composeBom = platform("androidx.compose:compose-bom:2025.09.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
}
