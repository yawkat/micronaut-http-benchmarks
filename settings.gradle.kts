pluginManagement {
    includeBuild("build-logic")
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
            variant("on")
        }
        dimension("json") {
            variant("jackson")
            variant("serde")
        }
    }
}
