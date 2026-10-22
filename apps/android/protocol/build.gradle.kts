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

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.json.schema.validator)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Repo-root protocol/ directory (schemas and committed golden files).
val protocolRoot = rootProject.projectDir.resolve("../../protocol").canonicalFile

tasks.test {
    useJUnitPlatform()
    systemProperty("proprio.protocol.dir", protocolRoot.absolutePath)
}

// Regenerates protocol/golden/*. Explicit only: never a dependency of `test`.
tasks.register<JavaExec>("generateGolden") {
    group = "verification"
    description = "Deterministically regenerates protocol/golden (zips and expected.json)."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("dev.proprio.protocol.GoldenGeneratorKt")
    args(protocolRoot.resolve("golden").absolutePath)
}
