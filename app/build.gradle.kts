import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * DebDroid v2 — 应用模块配置。
 *
 * 设计要点（对应 docs/architecture.md §5）：
 * - 单 arm64：ndk abiFilters 限定 arm64-v8a；rootfs/proot 资产统一放 src/main/assets
 * - targetSdk 28 硬约束：API 29+ 禁止执行应用私有目录中的可执行文件，proot 无法运行
 * - 签名：CI 注入 KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD；本地无 Secrets 时回退 debug
 * - versionCode：CI 传 -PversionCode=$GITHUB_RUN_NUMBER；本地默认 1（versionName 手工维护）
 */
val keystoreBase64: String? = System.getenv("KEYSTORE_BASE64")

android {
    namespace = "com.debdroid.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.debdroid.app"
        minSdk = 26
        targetSdk = 28
        // CI 注入：-PversionCode=$GITHUB_RUN_NUMBER；本地构建默认 1
        versionCode = (project.findProperty("versionCode") as String? ?: "1").toInt()
        versionName = "2.0.0"

        ndk {
            // 单 arm64（v2 决策 T-04）
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            if (!keystoreBase64.isNullOrEmpty()) {
                // 旧式：Secrets 直接给 base64（与 v1.x 兼容路径）
                val ksFile = rootProject.file("ci-release.keystore")
                ksFile.writeBytes(Base64.getDecoder().decode(keystoreBase64))
                storeFile = ksFile
            } else {
                // 新式：CI 已把 Secrets 恢复成文件（build.yml 步骤 2）
                storeFile = file(System.getenv("KEYSTORE_FILE") ?: "release.keystore")
            }
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS") ?: "debdroid"
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (
                !keystoreBase64.isNullOrEmpty() ||
                System.getenv("KEYSTORE_FILE") != null ||
                file("release.keystore").exists()
            ) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug") // 本地无密钥时回退，CI 永远走正式签名
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    // 终端内核与渲染（Termux v0.118.3，Apache-2.0，见 THIRD-PARTY-NOTICES.md）
    implementation(project(":terminal-emulator"))
    implementation(project(":terminal-view"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commons.compress) // rootfs.tar.xz 解压
    implementation(libs.xz)
    implementation(libs.nanohttpd) // 调试 HTTP 接口（DebugApiServer）

    testImplementation(libs.junit)
    testImplementation(libs.json) // DebugApiServer.applyJson 单测（org.json 本地 JVM 无内置）

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
}
