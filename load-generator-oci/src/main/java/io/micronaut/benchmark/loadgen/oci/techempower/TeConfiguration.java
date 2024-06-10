package io.micronaut.benchmark.loadgen.oci.techempower;

import io.micronaut.context.annotation.ConfigurationProperties;

@ConfigurationProperties("techempower")
public record TeConfiguration(
        String compartmentId,
        String region,
        String availabilityDomain
) {
}
