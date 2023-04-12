import org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask

/**
 * This plugin activates epoll support for the Micronaut application.
 */
plugins {
    id("io.micronaut.testcase")
}

dependencies {
    runtimeOnly("io.netty:netty-transport-native-unix-common:4.1.89.Final") // todo: needed to work with io_uring 0.0.19
    runtimeOnly("io.netty.incubator:netty-incubator-transport-native-io_uring:0.0.19.Final:linux-x86_64") // todo: use micronaut-managed version
}

tasks.withType<BuildNativeImageTask>().configureEach {
    enabled = false
}
