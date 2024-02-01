import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask

/**
 * This plugin activates epoll support for the Micronaut application.
 */
plugins {
    id("io.micronaut.testcase")
}

dependencies {
    runtimeOnly("io.netty.incubator:netty-incubator-transport-native-io_uring::linux-x86_64")
}

tasks.withType<BuildNativeImageTask>().configureEach {
    enabled = false
}
