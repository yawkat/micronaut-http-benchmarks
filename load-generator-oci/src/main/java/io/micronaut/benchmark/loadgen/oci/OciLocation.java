package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.EachProperty;

@EachProperty(value = "suite.location", list = true)
public record OciLocation(
        String compartmentId,
        String region,
        String availabilityDomain
) {
}
