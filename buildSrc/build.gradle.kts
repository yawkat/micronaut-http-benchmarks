plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("io.micronaut.gradle:micronaut-gradle-plugin:3.7.0")
    implementation("gradle.plugin.com.github.johnrengelman:shadow:7.1.2")
}
