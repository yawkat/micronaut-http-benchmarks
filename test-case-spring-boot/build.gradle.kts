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
    implementation("org.springframework.boot:spring-boot-starter-web:3.2.1") {
        exclude("org.springframework.boot", "spring-boot-starter-tomcat")
    }
    implementation("org.springframework.boot:spring-boot-starter-jetty:3.2.1")
    runtimeOnly("org.eclipse.jetty:jetty-alpn-server:12.0.5")
    runtimeOnly("org.eclipse.jetty:jetty-alpn-java-server:12.0.5")
    runtimeOnly("org.eclipse.jetty.http2:jetty-http2-server:12.0.5")
    runtimeOnly("ch.qos.logback:logback-classic:1.4.14")
    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.9.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}