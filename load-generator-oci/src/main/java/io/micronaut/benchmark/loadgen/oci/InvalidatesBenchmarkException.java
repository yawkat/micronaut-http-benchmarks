package io.micronaut.benchmark.loadgen.oci;

/**
 * Special exception that is never retried, to avoid bias where we only see "good" results. i.e. the results were so
 * bad it ran into limits of the load testing setup.
 */
public class InvalidatesBenchmarkException extends Exception {
    public InvalidatesBenchmarkException(String message) {
        super(message);
    }
}
