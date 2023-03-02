package io.micronaut.benchmark.loadgen.oci;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record LoadVariant(
        @JsonIgnore
        String name,
        Protocol protocol,
        int stringCount,
        int stringLength,
        @JsonIgnore
        byte[] body
) {
}
