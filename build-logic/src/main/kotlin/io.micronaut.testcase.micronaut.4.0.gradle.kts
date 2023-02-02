plugins {
    id("io.micronaut.testcase")
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots")
    }
}

micronaut {
    version("4.0.0-SNAPSHOT")
}
