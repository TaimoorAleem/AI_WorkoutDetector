plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Repo-root protocol/ directory (JSON Schemas used by the validation tests).
val protocolRoot = rootProject.projectDir.resolve("../../protocol").canonicalFile

android {
    namespace = "dev.proprio.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.proprio"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Backend base URL used until the user sets one in Settings. Override with -Pproprio.apiBaseUrl=...
        // (the debug default 10.0.2.2 is the host PC as seen from the emulator; a real phone needs the PC's LAN address).
        val apiBaseUrl = providers.gradleProperty("proprio.apiBaseUrl").orNull
        buildConfigField("String", "DEFAULT_API_BASE_URL_DEBUG", "\"${apiBaseUrl ?: "http://10.0.2.2:8080"}\"")
        buildConfigField("String", "DEFAULT_API_BASE_URL_RELEASE", "\"${apiBaseUrl ?: "https://api.proprio.invalid"}\"")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.systemProperty("proprio.protocol.dir", protocolRoot.absolutePath) }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(project(":backend"))
    implementation(libs.work.runtime.ktx)
    implementation(libs.tink.android)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.play.services.wearable)
    implementation(libs.wear.remote.interactions)
    implementation(libs.concurrent.futures.ktx)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.work.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json.schema.validator)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
