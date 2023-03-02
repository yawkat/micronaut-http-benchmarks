package io.micronaut.benchmark.loadgen.oci;

public record OciLocation(
        String compartmentId,
        String availabilityDomain
) {
}
