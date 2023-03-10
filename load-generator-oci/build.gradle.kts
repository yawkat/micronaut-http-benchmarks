plugins {
    id("io.micronaut.application")
}

group = "org.example"
version = "unspecified"

repositories {
    mavenCentral()
    mavenLocal()
    maven {
        url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots")
    }
}

dependencies {
    runtimeOnly("org.yaml:snakeyaml")
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-sdk")
    implementation("io.micronaut.toml:micronaut-toml")
    implementation("io.micronaut:micronaut-jackson-databind")
    implementation("io.micronaut:micronaut-http-client")
    implementation("com.oracle.oci.sdk:oci-java-sdk-core:3.4.0")
    implementation("com.oracle.oci.sdk:oci-java-sdk-identity:3.4.0")
    implementation("io.hyperfoil:hyperfoil-api:0.24.1")
    implementation("io.hyperfoil:hyperfoil-core:0.24.1")
    implementation("io.hyperfoil:hyperfoil-clustering:0.24.1")
}

micronaut {
    version("4.0.0-SNAPSHOT")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("io.micronaut.benchmark.loadgen.oci.*")
    }
}
