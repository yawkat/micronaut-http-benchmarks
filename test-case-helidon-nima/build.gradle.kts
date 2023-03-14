plugins {
    id("java")
    id("application")
    id("com.github.johnrengelman.shadow")
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("org.example.Main")
}

dependencies {
    implementation("io.helidon.nima.webserver:helidon-nima-webserver:4.0.0-ALPHA5")
    implementation("io.helidon.nima.http2:helidon-nima-http2-webserver:4.0.0-ALPHA5")
    implementation("io.helidon.nima.http.media:helidon-nima-http-media-jsonb:4.0.0-ALPHA5")

    // for self-signed cert generation
    implementation("io.netty:netty-handler:4.1.89.Final")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.72")

    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.9.2")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.14.2")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(19))
    }
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
    jvmArgs(listOf("--enable-preview"))
}