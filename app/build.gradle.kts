import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.rainy.screenshot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rainy.screenshot"
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        val keystoreFile = rootProject.file("release.jks")
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                // 凭据必须通过环境变量注入；缺失或为空时直接报错，不提供任何隐式 fallback
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("KEYSTORE_PASSWORD env var not set or empty — cannot sign release")
                keyAlias = System.getenv("KEYSTORE_ALIAS")
                    ?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("KEYSTORE_ALIAS env var not set or empty — cannot sign release")
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?.takeIf { it.isNotBlank() }
                    ?: throw GradleException("KEY_PASSWORD env var not set or empty — cannot sign release")
            }
        } else if (System.getenv("CI") != null) {
            // 仅 CI 环境（release workflow 未注入 Secret 的 PR 构建等）fallback 到 debug keystore
            create("release") {
                val debugKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
                storeFile = debugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        // 本地无 release.jks 且非 CI：不创建 release 签名配置，
        // assembleRelease 产出 unsigned APK（诚实失败，绝不静默 debug 签名），
        // assembleDebug 不受影响。
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // 仅在 release.jks（或 CI fallback）存在时挂接签名
            signingConfig = signingConfigs.findByName("release")
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// ═══════════════════════════════════════════════════════
// AGP 9.0 ARM64 Proot: Release resource guard + fallback
//（与 RainyToken 相同的兼容方案，见其 app/build.gradle.kts）
// ═══════════════════════════════════════════════════════
if (System.getProperty("os.arch") == "aarch64") {
    tasks.register("guardReleaseResources") {
        dependsOn("optimizeReleaseResources")
        doLast {
            val linkedAp = layout.buildDirectory
                .file("intermediates/linked_resources_binary_format/release/processReleaseResources/linked-resources-binary-format-release.ap_")
                .get().asFile
            val optimizedDir = layout.buildDirectory
                .dir("intermediates/optimized_processed_res/release/optimizeReleaseResources")
                .get().asFile
            val optimizedAp = File(optimizedDir, "resources-release-optimize.ap_")

            if (optimizedAp.exists() && optimizedAp.length() > 0) {
                val zipList = providers.exec {
                    commandLine("unzip", "-l", optimizedAp.absolutePath)
                    isIgnoreExitValue = true
                }.standardOutput.asText.get()

                val hasManifest = zipList.contains("AndroidManifest.xml")
                val hasArsc = zipList.contains("resources.arsc")
                val hasRes = zipList.contains("res/")

                if (hasManifest && hasArsc && hasRes) {
                    logger.lifecycle("GuardReleaseResources: optimized .ap_ OK (${optimizedAp.length()} bytes)")
                    return@doLast
                }
                logger.warn("GuardReleaseResources: optimized .ap_ incomplete (manifest=$hasManifest arsc=$hasArsc res=$hasRes)")
            } else {
                logger.warn("GuardReleaseResources: optimized .ap_ missing or empty")
            }

            if (!linkedAp.exists() || linkedAp.length() == 0L) {
                throw GradleException("GuardReleaseResources: linked .ap_ also missing or empty — cannot recover")
            }

            optimizedDir.mkdirs()
            linkedAp.copyTo(optimizedAp, overwrite = true)

            val verifyList = providers.exec {
                commandLine("unzip", "-l", optimizedAp.absolutePath)
                isIgnoreExitValue = true
            }.standardOutput.asText.get()

            val vManifest = verifyList.contains("AndroidManifest.xml")
            val vArsc = verifyList.contains("resources.arsc")
            val vRes = verifyList.contains("res/")

            if (!vManifest || !vArsc || !vRes) {
                throw GradleException("GuardReleaseResources: fallback copy verification failed (manifest=$vManifest arsc=$vArsc res=$vRes)")
            }

            logger.lifecycle("GuardReleaseResources: fallback — copied linked .ap_ → optimized .ap_ (${optimizedAp.length()} bytes, path shortening skipped)")
        }
    }

    project.tasks.matching { it.name == "packageRelease" }.configureEach {
        dependsOn("guardReleaseResources")
    }
}

// Force ARM64 AAPT2 in Proot environment (local only; GitHub Actions x86_64 uses default)
if (System.getProperty("os.arch") == "aarch64") {
    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "com.android.tools.build" && requested.name == "aapt2") {
                useTarget("com.android.tools.build:aapt2:${'$'}{requested.version}:linux-aarch64")
            }
        }
    }
}

dependencies {

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // DataStore（设置持久化）
    implementation(libs.androidx.datastore.preferences)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Shizuku
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // DI (Hilt + KSP)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}