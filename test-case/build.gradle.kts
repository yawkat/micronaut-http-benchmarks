import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask

plugins {
    id("io.micronaut.bench.variants")
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
    implementation("io.micronaut:micronaut-http-client")
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("ch.qos.logback:logback-classic")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

benchmarkVariants {
    variant("tcnative") {
        runtimeDependency("io.netty:netty-tcnative-boringssl-static:2.0.46.Final")
    }
    variant("json") {
        // this is stupid but only to show how to add a runtime dependency specific to a variant
        runtimeDependency("io.micronaut.problem:micronaut-problem-json")
    }
}
