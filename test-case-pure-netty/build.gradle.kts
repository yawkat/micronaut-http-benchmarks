
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
    implementation("io.netty:netty-codec-http:4.1.104.Final")
    implementation("io.netty:netty-codec-http2:4.1.104.Final")
    implementation("io.netty.incubator:netty-incubator-transport-native-io_uring:0.0.24.Final:linux-x86_64")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.77")
    runtimeOnly("ch.qos.logback:logback-classic:1.4.14")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.9.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
