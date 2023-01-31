package io.micronaut.bench

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.graalvm.buildtools.gradle.dsl.GraalVMExtension
import org.gradle.api.Action
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.plugins.JavaApplication
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.exclude
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

    fun combinations(spec: Action<in DimensionsSpec>) = DimensionsSpec().run {
        spec.execute(this)
        dimensions.values.map { it.variants.entries.toList() }.combinations().forEach {
            val variantName = it.mapIndexed { i, s ->
                val baseName = s.value.dimensionName + s.key.capitalize()
                if (i == 0) {
                    baseName
                } else {
                    baseName.capitalize()
                }
            }.joinToString("")
            if (!excludedVariants.contains(variantName)) {
                buildVariant(variantName, it.map { it.value })
            }
        }
    }


    private fun buildVariant(variantName: String, variantSpecs: List<VariantSpec>) {
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
        variantSpecs.forEach { v ->
            v.runtimeDependencies.forEach {
                dependencies.add(dependenciesConf.name, it)
            }
            v.runtimeExcludes.forEach {
                val dep = dependencies.create(it) as ModuleDependency
                classpathConf.exclude(dep.group, dep.name)
            }
        }
    }

    class DimensionsSpec {
        val dimensions = mutableMapOf<String, DimensionSpec>()
        val excludedVariants = mutableSetOf<String>()

        fun dimension(dimensionName: String, dimensionSpec: Action<in DimensionSpec>) {
            dimensions.put(dimensionName, DimensionSpec(dimensionName).apply { dimensionSpec.execute(this) })
        }

        fun exclude(variantName: String) {
            excludedVariants.add(variantName)
        }
    }

    class DimensionSpec(val name: String) {
        val variants = mutableMapOf<String, VariantSpec>()

        fun variant(variantName: String, spec: Action<in VariantSpec>) = variants.put(variantName, VariantSpec(name).apply { spec.execute(this) })
    }

    class VariantSpec(val dimensionName: String) {
        internal val runtimeDependencies = mutableListOf<Any>()
        internal val runtimeExcludes = mutableListOf<Any>()

        fun runtimeDependency(notation: Any) = runtimeDependencies.add(notation)
        fun exclude(notation: Any) = runtimeExcludes.add(notation)
    }
}

inline fun <reified T> List<List<T>>.combinations(): List<List<T>> {
    val result = mutableListOf(mutableListOf<T>())
    for (list in this) {
        val temp = mutableListOf<MutableList<T>>()
        for (item in list) {
            for (combination in result) {
                temp.add(mutableListOf(*combination.toTypedArray(), item))
            }
        }
        result.clear()
        result.addAll(temp)
    }
    return result
}
