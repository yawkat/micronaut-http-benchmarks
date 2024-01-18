package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("variants.hotspot")
public record HotspotConfiguration(int version, String commonOptions, List<String> optionChoices) {
}
