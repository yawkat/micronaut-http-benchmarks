package io.micronaut.benchmark.loadgen.oci;

import org.apache.sshd.client.session.ClientSession;

public interface FrameworkRun {
    String type();

    String name();

    Object parameters();

    void setupAndRun(
            ClientSession benchmarkServerClient,
            OutputListener.Write log,
            BenchmarkClosure benchmarkClosure
    ) throws Exception;

    interface BenchmarkClosure {
        void benchmark() throws Exception;
    }
}
