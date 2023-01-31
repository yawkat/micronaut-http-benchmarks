plugins {
    id("java")
    id("io.micronaut.application") version "3.7.0"
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
    implementation(project(":test-case"))
    runtimeOnly("io.netty:netty-tcnative-boringssl-static:2.0.46.Final")
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}