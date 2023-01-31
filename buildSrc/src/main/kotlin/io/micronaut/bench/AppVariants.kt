package io.micronaut.bench

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.graalvm.buildtools.gradle.dsl.GraalVMExtension
import org.gradle.api.Action
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskContainer
import javax.inject.Inject

abstract class AppVariants(
    @Inject private val graalvmNative: GraalVMExtension,
    @Inject private val application: JavaApplication,
    @Inject private val sourceSets: SourceSetContainer
) {

    @get:Inject
    protected abstract val configurations: ConfigurationContainer

    @get:Inject
    protected abstract val tasks: TaskContainer

    @get:Inject
    protected abstract val dependencies: DependencyHandler

    fun variant(variantName: String, spec: Action<in Spec>) {
        val dependenciesConf = configurations.create("${variantName}Variant") {
            isCanBeConsumed = false
            isCanBeResolved = false
        }
        val classpathConf = configurations.create("${variantName}VariantClasspath") {
            isCanBeConsumed = false
            isCanBeResolved = true
            extendsFrom(configurations.getByName("nativeImageClasspath"))
            extendsFrom(dependenciesConf)
        }

        graalvmNative.binaries {
            create(variantName) {
                mainClass.set(application.mainClass)
                classpath(classpathConf)
            }
        }

        tasks.register("shadow${variantName.capitalize()}Jar", ShadowJar::class.java) {
            archiveClassifier.set(variantName)
            from(sourceSets.getByName("main").output)
            configurations = listOf(classpathConf)
            manifest {
                attributes.put("Main-Class", application.mainClass.get())
            }
            exclude("META-INF/INDEX.LIST", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
        }
        spec.execute(object : Spec {
            override fun runtimeDependency(notation: Any) {
                dependencies.add(dependenciesConf.name, notation)
            }
        })
    }

    interface Spec {
        fun runtimeDependency(notation: Any)
    }
}
