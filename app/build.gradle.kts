import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
}

// Read signing creds from local.properties first, then env vars (CI).
val signingProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingCred(key: String): String? =
    signingProps.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: System.getenv(key)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.openclaw.ghostcrab"
    compileSdk = 36

    val gitSha: String = try {
        providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }
            .standardOutput.asText.get().trim()
    } catch (_: Exception) { "unknown" }

    defaultConfig {
        applicationId = "com.openclaw.ghostcrab"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // AI recommendations feature flag — true for v1.0; flip false to gate behind a pro paywall.
        buildConfigField("Boolean", "AI_PRO_ENABLED", "true")
        // Git SHA baked in at build time — exposed on the About screen.
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
        // Skill install foundation — dark in release until we ship it.
        buildConfigField("Boolean", "SKILLS_INSTALL_ENABLED", "false")
    }

    signingConfigs {
        create("release") {
            val keystorePath = signingCred("KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = signingCred("KEYSTORE_PASSWORD")
                keyAlias = signingCred("KEY_ALIAS")
                keyPassword = signingCred("KEY_PASSWORD") ?: signingCred("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("Boolean", "DEBUG_BUILD", "true")
            buildConfigField("Boolean", "SKILLS_INSTALL_ENABLED", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signingCred("KEYSTORE_PATH") != null) {
                signingConfigs.getByName("release")
            } else {
                null
            }
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

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
            it.jvmArgs("-Xmx1g")
            // Use a unique binary results path per invocation to avoid Windows file-lock issues.
            // The old directories accumulate in TEMP but are small; clean them manually as needed.
            it.binaryResultsDirectory.set(
                file("${System.getProperty("java.io.tmpdir")}/ghostcrab-test-results-${System.nanoTime()}")
            )
        }
    }
    buildToolsVersion = "36.0.0"
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Convenience: print the AAB path after a successful bundle task
tasks.whenTaskAdded {
    if (name == "bundleRelease") {
        doLast {
            val aab = file("$buildDir/outputs/bundle/release/app-release.aab")
            if (aab.exists()) println("AAB: ${aab.absolutePath}")
        }
    }
}

detekt {
    config.setFrom(rootProject.file("config/detekt.yml"))
    buildUponDefaultConfig = true
    baseline = rootProject.file("config/detekt-baseline.xml")
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    baseline.set(rootProject.file("config/detekt-baseline.xml"))
}
tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
    baseline.set(rootProject.file("config/detekt-baseline.xml"))
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.navigation.compose)
    implementation(libs.splashscreen)

    // Ktor
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.websockets)

    // Koin
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    // DataStore + Security
    implementation(libs.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Coil
    implementation(libs.coil.compose)

    // Google Fonts (Inter, JetBrains Mono)
    implementation(libs.compose.ui.text.google.fonts)

    // CameraX
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    // CameraX 1.6 dropped the transitive Guava ListenableFuture; restore it.
    implementation(libs.guava)

    // ML Kit QR decode (on-device, no network call)
    implementation(libs.mlkit.barcode.scanning)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    debugImplementation(libs.leakcanary.android)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.ktor.client.mock)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
