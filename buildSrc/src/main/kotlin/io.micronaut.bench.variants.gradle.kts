import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import io.micronaut.bench.AppVariants
import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask

plugins {
    id("io.micronaut.application")
    id("com.github.johnrengelman.shadow")
}

val variants = extensions.create<AppVariants>("benchmarkVariants", graalvmNative, application, sourceSets)


tasks.register("nativeImages") {
    dependsOn(tasks.withType<BuildNativeImageTask>())
}

tasks.register("shadowJars") {
    dependsOn(tasks.withType<ShadowJar>())
}

tasks.register("allVariants") {
    dependsOn("nativeImages", "shadowJars")
}
