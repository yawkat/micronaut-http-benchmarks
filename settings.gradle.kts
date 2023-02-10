pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots")
        }
    }
}

plugins {
    id("io.micronaut.bench.variants")
}

rootProject.name = "micronaut-benchmark"

include("load-generator-gatling")

configure<io.micronaut.bench.AppVariants> {
    combinations {
        dimension("tcnative") {
            variant("off")
            variant("on")
        }
        dimension("epoll") {
            variant("off")
            //variant("on")
        }
        dimension("json") {
            variant("jackson")
            //variant("serde")
        }
        dimension("micronaut") {
            variant("3.8")
            variant("4.0")
        }
        dimension("java") {
            //variant("11")
            variant("17")
        }
        exclude {
            // Combination of Micronaut 4 and Java 11 is invalid
            it.contains("micronaut-4.0") && it.contains("java-11")
        }
    }
}
