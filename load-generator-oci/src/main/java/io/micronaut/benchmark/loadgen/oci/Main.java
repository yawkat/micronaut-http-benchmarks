package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.runtime.Micronaut;

public class Main {
    public static void main(String[] args) throws Exception {
        Micronaut.run(args).getBean(SuiteRunner.class).run();
    }
}
