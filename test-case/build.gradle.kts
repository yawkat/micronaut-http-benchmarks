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

benchmarkVariants.combinations {
    dimension("tcnative") {
        variant("off") {

        }
        variant("on") {
            runtimeDependency("io.netty:netty-tcnative-boringssl-static:2.0.46.Final")
        }
    }
    dimension("epoll") {
        variant("off") {
            runtimeDependency("io.netty:netty-transport-native-epoll:4.1.70.Final")
        }
        variant("on") {

        }
    }
    dimension("json") {
        variant("jackson") {
            runtimeDependency("io.micronaut:micronaut-jackson-databind")
        }
        variant("serde") {
            runtimeDependency("io.micronaut.serde:micronaut-serde-jackson")
            exclude("io.micronaut:micronaut-jackson-databind")
        }
    }
}
