package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;

@ConfigurationProperties("variants.hotspot.async-profiler")
public record AsyncProfilerConfiguration(boolean enabled, Path path, String args, List<String> outputs) {
}
