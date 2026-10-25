import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.openapi.generator") version "7.14.0"
    id("com.diffplug.spotless") version "7.2.1"
}

group = "dev.proprio"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val awsSdkVersion = "2.57.3"
val networkntVersion = "1.5.9"
val springdocVersion = "2.8.17"

dependencyManagement {
    imports {
        mavenBom("software.amazon.awssdk:bom:$awsSdkVersion")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    implementation("software.amazon.awssdk:s3")
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    implementation("com.networknt:json-schema-validator:$networkntVersion")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:rabbitmq")
    testImplementation("org.testcontainers:minio")
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ---- Contract-first code generation -------------------------------------------------------
val contractFile = layout.projectDirectory.file("../../contracts/openapi/core-api.yaml")
val generatedDir = layout.buildDirectory.dir("generated/openapi")

val openApiGenerateServer =
    tasks.register<GenerateTask>("openApiGenerateServer") {
        generatorName.set("spring")
        inputSpec.set(contractFile.asFile.toURI().toString())
        outputDir.set(generatedDir.get().asFile.absolutePath)
        apiPackage.set("dev.proprio.coreapi.api")
        modelPackage.set("dev.proprio.coreapi.api.model")
        // LabelsDocument is a free-form object; a hand-written Map-based class replaces the generated one.
        importMappings.set(mapOf("LabelsDocument" to "dev.proprio.coreapi.labels.LabelsDocument"))
        schemaMappings.set(mapOf("LabelsDocument" to "dev.proprio.coreapi.labels.LabelsDocument"))
        generateApiTests.set(false)
        generateModelTests.set(false)
        generateApiDocumentation.set(false)
        generateModelDocumentation.set(false)
        configOptions.set(
            mapOf(
                "interfaceOnly" to "true",
                "useSpringBoot3" to "true",
                "useTags" to "true",
                "skipDefaultInterface" to "true",
                "useBeanValidation" to "true",
                "performBeanValidation" to "false",
                "openApiNullable" to "false",
                "dateLibrary" to "java8",
                "documentationProvider" to "none",
                "annotationLibrary" to "none",
                "useResponseEntity" to "true",
                "hideGenerationTimestamp" to "true",
                "sourceFolder" to "src/main/java",
            ),
        )
        // The generator initialises the Map-typed `labels` property with `new HashMap<>()`, which does not
        // compile against our LabelsDocument subclass of LinkedHashMap. Patch that one initialiser.
        doLast {
            val f =
                generatedDir
                    .get()
                    .file("src/main/java/dev/proprio/coreapi/api/model/DataExportSessionsInner.java")
                    .asFile
            f.writeText(
                f.readText().replace(
                    "LabelsDocument labels = new HashMap<>();",
                    "LabelsDocument labels = new dev.proprio.coreapi.labels.LabelsDocument();",
                ),
            )
        }
    }

val copySchemas =
    tasks.register<Copy>("copySchemas") {
        from("../../contracts/events") { into("contracts/events") }
        from("../../protocol/schema") { into("protocol/schema") }
        into(layout.buildDirectory.dir("generated/schemas"))
    }

sourceSets {
    main {
        java {
            srcDir(generatedDir.map { it.dir("src/main/java") })
        }
        resources {
            // Shared schemas are copied at build time, never duplicated in git.
            srcDir(layout.buildDirectory.dir("generated/schemas"))
        }
    }
}

tasks.compileJava { dependsOn(openApiGenerateServer) }
tasks.processResources { dependsOn(copySchemas) }

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

val protocolDir =
    layout.projectDirectory
        .dir("../../protocol")
        .asFile.canonicalPath
val contractsDir =
    layout.projectDirectory
        .dir("../../contracts")
        .asFile.canonicalPath

tasks.test {
    useJUnitPlatform()
    systemProperty("proprio.protocol.dir", protocolDir)
    systemProperty("proprio.contracts.dir", contractsDir)
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Fast loop without Docker: ./gradlew unitTest
tasks.register<Test>("unitTest") {
    description = "Runs only tests not tagged 'integration' (no Docker needed)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { excludeTags("integration") }
    systemProperty("proprio.protocol.dir", protocolDir)
    systemProperty("proprio.contracts.dir", contractsDir)
}

tasks.bootJar { archiveFileName.set("core-api.jar") }
