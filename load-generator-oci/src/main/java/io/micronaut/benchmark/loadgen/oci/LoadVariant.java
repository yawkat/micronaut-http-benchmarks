package io.micronaut.benchmark.loadgen.oci;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record LoadVariant(
        String name,
        Protocol protocol,
        @JsonIgnore
        byte[] body
) {
}
