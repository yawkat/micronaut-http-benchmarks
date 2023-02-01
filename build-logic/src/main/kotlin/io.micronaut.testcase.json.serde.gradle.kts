/**
 * This plugin replaces Jackson with Micronaut Serde at runtime.
 */
plugins {
    id("io.micronaut.testcase")
}

configurations.all {
    exclude(group="io.micronaut", module="micronaut-jackson-databind")
}

dependencies {
    runtimeOnly("io.micronaut.serde:micronaut-serde-jackson")
}
