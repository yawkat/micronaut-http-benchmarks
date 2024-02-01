package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.core.bind.annotation.Bindable;

import java.util.List;

@EachProperty(value = "load.protocols", list = true)
public record ProtocolSettings(
        Protocol protocol,
        int sharedConnections,
        @Bindable(defaultValue = "1")
        int pipeliningLimit,
        @Bindable(defaultValue = "1")
        int maxHttp2Streams,
        int compileOps,
        List<Integer> ops
) {
}
