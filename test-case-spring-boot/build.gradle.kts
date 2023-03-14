plugins {
    id("java")
    id("application")
    id("org.springframework.boot") version "3.0.4"
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("org.example.Main")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web:3.0.4")
    runtimeOnly("ch.qos.logback:logback-classic:1.4.5")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.9.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}