package io.micronaut.benchmark.loadgen.oci;

import org.apache.sshd.client.session.ClientSession;

import java.util.function.Consumer;

public interface FrameworkRun {
    String type();

    String name();

    Object parameters();

    void setupAndRun(
            ClientSession benchmarkServerClient,
            OutputListener.Write log,
            BenchmarkClosure benchmarkClosure,
            Consumer<BenchmarkPhase> progress) throws Exception;

    interface BenchmarkClosure {
        void benchmark() throws Exception;
    }
}
