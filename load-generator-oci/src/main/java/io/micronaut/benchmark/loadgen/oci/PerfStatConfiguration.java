package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("perf-stat")
public record PerfStatConfiguration(
        boolean enabled,
        Duration interval
) {
    String asCommandPrefix() {
        if (!enabled) {
            return "";
        }
        return "perf stat -I " + interval.toMillis() + " ";
    }
}
