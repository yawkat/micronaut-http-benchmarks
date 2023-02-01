/**
 * This plugin activates epoll support for the Micronaut application.
 */
plugins {
    id("io.micronaut.testcase")
}

dependencies {
    runtimeOnly("io.netty:netty-transport-native-epoll")
}
