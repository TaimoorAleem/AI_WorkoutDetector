import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.openapi.generator)
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

// The contract is the source of truth; the client is generated from it at build time and never committed.
val contractFile = rootProject.projectDir.resolve("../../contracts/openapi/core-api.yaml").canonicalFile
val generatedDir = layout.buildDirectory.dir("generated/openapi")

val generateClient = tasks.register<GenerateTask>("generateClient") {
    group = "openapi tools"
    description = "Generates the Kotlin (Retrofit + kotlinx.serialization) client from contracts/openapi/core-api.yaml."
    generatorName.set("kotlin")
    library.set("jvm-retrofit2")
    inputSpec.set(contractFile.toURI().toString())
    outputDir.set(generatedDir.get().asFile.absolutePath)
    packageName.set("dev.proprio.api")
    apiPackage.set("dev.proprio.api.apis")
    modelPackage.set("dev.proprio.api.models")
    generateApiTests.set(false)
    generateModelTests.set(false)
    generateApiDocumentation.set(false)
    generateModelDocumentation.set(false)
    // Free-form objects (labels.json) become Map<String, JsonElement>: kotlinx.serialization cannot encode Any.
    typeMappings.set(mapOf("AnyType" to "JsonElement"))
    importMappings.set(mapOf("JsonElement" to "kotlinx.serialization.json.JsonElement"))
    configOptions.set(
        mapOf(
            "serializationLibrary" to "kotlinx_serialization",
            "useCoroutines" to "true",
            "dateLibrary" to "java8",
            "omitGradleWrapper" to "true",
        ),
    )
    // Kotlin emits `Map<String, ? extends JsonElement>` for request parameters and Retrofit rejects wildcards.
    doLast {
        fileTree(generatedDir).matching { include("**/*.kt") }.forEach { f ->
            val text = f.readText()
            val patched = text.replace(
                "kotlin.collections.Map<kotlin.String, kotlinx.serialization.json.JsonElement>",
                "kotlin.collections.Map<kotlin.String, @JvmSuppressWildcards kotlinx.serialization.json.JsonElement>",
            )
            if (patched != text) f.writeText(patched)
        }
    }
}

sourceSets.main {
    kotlin.srcDir(generatedDir.map { it.dir("src/main/kotlin") })
}
tasks.named("compileKotlin") { dependsOn(generateClient) }

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.retrofit)
    api(libs.retrofit.converter.kotlinx)
    api(libs.retrofit.converter.scalars)
    api(libs.okhttp)
    // Referenced by the generated ApiClient default builder, which the app never uses.
    implementation(libs.okhttp.logging)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.snakeyaml)
    testImplementation(libs.kotlinx.coroutines.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    systemProperty("proprio.contract", contractFile.absolutePath)
}
