package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("variants.hotspot")
public record HotspotConfiguration(int version, List<String> optionChoices) {
}
