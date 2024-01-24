package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("meminfo")
public record MeminfoConfiguration(
        boolean enabled,
        Duration interval
) {
}
