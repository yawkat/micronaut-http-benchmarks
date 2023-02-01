/**
 * This plugin must be applied on "test case" projects and will configure
 * it as a Micronaut application. It should define the "common" code
 * for all benchmarks.
 */
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.repositories
import org.gradle.kotlin.dsl.withType

plugins {
    id("io.micronaut.application")
    id("com.github.johnrengelman.shadow")
}

repositories {
    mavenCentral()
}

micronaut {
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("org.example.*")
    }
}

application {
    mainClass.set("org.example.Main")
}

dependencies {
    annotationProcessor("io.micronaut:micronaut-http-validation")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    compileOnly("io.micronaut.serde:micronaut-serde-api")
    implementation("io.micronaut:micronaut-http-client")
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("ch.qos.logback:logback-classic")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val artifactName = project.path.replace(":", "-").substring(":test-case:".length)

tasks.withType<Jar>().configureEach {
    archiveBaseName.set(artifactName)
}

graalvmNative.binaries.all {
    imageName.set(artifactName)
}

// The following configurations are used to aggregate the shadowJar and nativeImage tasks
// So that the root project can collect them all in a single directory

val shadowJars by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    outgoing.artifact(tasks.named("shadowJar"))
}

val nativeImages by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    outgoing.artifact(tasks.named("nativeCompile"))
}
