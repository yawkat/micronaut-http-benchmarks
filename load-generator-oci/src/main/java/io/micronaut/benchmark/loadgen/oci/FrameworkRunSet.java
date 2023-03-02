package io.micronaut.benchmark.loadgen.oci;

import java.util.List;

public interface FrameworkRunSet {
    List<? extends FrameworkRun> getRuns();
}
