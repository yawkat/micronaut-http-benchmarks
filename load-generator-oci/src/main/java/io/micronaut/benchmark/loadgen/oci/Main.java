package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.Micronaut;

public class Main {
    public static void main(String[] args) throws Exception {
        ApplicationContext ctx = Micronaut.run(args);
        ctx.getBean(RegionCustomizer.class).setUp();
        ctx.getBean(SuiteRunner.class).run();
    }
}
