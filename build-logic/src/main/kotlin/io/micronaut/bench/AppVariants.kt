package io.micronaut.bench

import org.gradle.api.Action
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import java.io.File
import javax.inject.Inject

abstract class AppVariants(val settings: Settings) {

    @get:Inject
    abstract val gradle: Gradle

    fun combinations(spec: Action<in DimensionsSpec>) = DimensionsSpec().run {
        spec.execute(this)
        dimensions.values.map { it.variants.entries.toList() }.combinations().forEach {
            val path = it.map { s ->
                "${s.value.dimensionName}-${s.key}"
            }.joinToString("/")
            if (!excludedVariants.contains(path)) {
                buildVariant(path, it.map { it.value })
            }
        }
    }

    private fun buildVariant(path: String, variantSpecs: List<VariantSpec>) {
        val projectPath = ":test-case:${path.replace('/', ':')}"
        settings.include(projectPath)
        settings.project(projectPath).setProjectDir(File(settings.rootDir, "test-case-common"))
        gradle.beforeProject {
            if (this.path == projectPath) {
                setBuildDir(File(projectDir, "build/${path}"))
            }
        }
        gradle.afterProject {
            if (this.path == projectPath) {
                variantSpecs.forEach {
                    val pluginId = "io.micronaut.testcase.${it.dimensionName}.${it.name}"
                    val plugin = File(settings.settingsDir, "build-logic/src/main/kotlin/$pluginId.gradle.kts")
                    if (plugin.exists()) {
                        plugins.apply(pluginId)
                    }
                }
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

        fun variant(variantName: String) {
            variants.put(variantName, VariantSpec(name, variantName))
        }
    }


    class VariantSpec(val dimensionName: String, val name: String)
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
