plugins {
    id("io.micronaut.testcase")
}

micronaut {
    version("3.8.3")
}

dependencies {
    micronautBoms(platform("io.micronaut:micronaut-bom:3.8.3"))
}

configurations.all {
    exclude(group = "io.micronaut.platform")
}
