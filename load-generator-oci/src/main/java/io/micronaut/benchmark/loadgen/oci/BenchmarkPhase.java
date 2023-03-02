package io.micronaut.benchmark.loadgen.oci;

public enum BenchmarkPhase {
    BEFORE,
    CREATING_VCN,
    SETTING_UP_NETWORK,
    SETTING_UP_INSTANCES,
    DEPLOYING,
    BENCHMARKING,
    SHUTTING_DOWN,
    DONE,
    FAILED,
}
