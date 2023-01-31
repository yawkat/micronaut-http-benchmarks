import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask

plugins {
    id("java")
    id("io.micronaut.application") version "3.7.0"
    id("com.github.johnrengelman.shadow") version "7.1.2"
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

val tcnative by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = false
}

val tcnativeImageClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations.nativeImageClasspath.get())
    extendsFrom(tcnative)
}

dependencies {
    annotationProcessor("io.micronaut:micronaut-http-validation")
    implementation("io.micronaut:micronaut-http-client")
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("ch.qos.logback:logback-classic")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")

    tcnative("io.netty:netty-tcnative-boringssl-static:2.0.46.Final")
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}

graalvmNative {
    binaries {
        create("tcnative") {
            mainClass.set(application.mainClass)
            classpath(tcnativeImageClasspath)
        }
    }
}

tasks.register<ShadowJar>("shadowTcnativeJar") {
    archiveClassifier.set("tcnative")
    from(sourceSets.main.get().output)
    configurations = listOf(tcnativeImageClasspath)
    manifest {
        attributes.put("Main-Class", application.mainClass.get())
    }
    exclude("META-INF/INDEX.LIST", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
}

tasks.register("nativeImages") {
    dependsOn(tasks.withType<BuildNativeImageTask>())
}

tasks.register("shadowJars") {
    dependsOn(tasks.withType<ShadowJar>())
}

tasks.register("allVariants") {
    dependsOn("nativeImages", "shadowJars")
}
