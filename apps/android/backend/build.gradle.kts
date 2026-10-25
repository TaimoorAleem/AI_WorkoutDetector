plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Pure-JVM backend layer shared by the app and the JVM tests: auth, the upload core, analysis mapping.
dependencies {
    api(project(":api-client"))
    api(libs.kotlinx.coroutines.core)
    implementation(project(":protocol"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Repo-root protocol/ directory (the golden session used by the optional stack E2E).
val protocolRoot = rootProject.projectDir.resolve("../../protocol").canonicalFile

tasks.test {
    useJUnitPlatform()
    systemProperty("proprio.protocol.dir", protocolRoot.absolutePath)
    // StackE2ETest only runs when PROPRIO_E2E_BASE_URL is set (e.g. http://localhost:8080) against a live stack.
    val e2e = providers.environmentVariable("PROPRIO_E2E_BASE_URL").orElse("")
    systemProperty("proprio.e2e.baseUrl", e2e.get())
    inputs.property("e2eBaseUrl", e2e)
}
