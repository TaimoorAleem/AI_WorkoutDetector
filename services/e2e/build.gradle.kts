plugins {
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.rest-assured:rest-assured:5.5.6")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.19.2")
}

val goldenDir =
    layout.projectDirectory
        .dir("../../protocol/golden")
        .asFile.canonicalPath

// These tests need a running stack (deploy/compose.yaml), so the plain `test` task skips them.
tasks.test {
    useJUnitPlatform { excludeTags("e2e") }
}

tasks.register<Test>("e2eTest") {
    description = "Runs the end-to-end suite against a running compose stack (E2E_BASE_URL, default http://localhost:8080)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("e2e") }
    systemProperty("proprio.golden.dir", goldenDir)
    environment("E2E_BASE_URL", System.getenv("E2E_BASE_URL") ?: "http://localhost:8080")
    // Always rerun: the result depends on the stack, not on the build inputs.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
