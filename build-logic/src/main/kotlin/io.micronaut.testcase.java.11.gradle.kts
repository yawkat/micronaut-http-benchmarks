plugins {
    id("io.micronaut.testcase")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
}
