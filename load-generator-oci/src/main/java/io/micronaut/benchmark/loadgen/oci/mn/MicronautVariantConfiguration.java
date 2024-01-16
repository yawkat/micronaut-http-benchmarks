package io.micronaut.benchmark.loadgen.oci.mn;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@ConfigurationProperties("variants.micronaut")
public record MicronautVariantConfiguration(Map<String, List<String>> compileVariants) {
}
