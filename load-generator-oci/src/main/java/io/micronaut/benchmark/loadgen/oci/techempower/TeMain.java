package io.micronaut.benchmark.loadgen.oci.techempower;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.Micronaut;

public class TeMain {
    public static void main(String[] args) throws Exception {
        ApplicationContext ctx = Micronaut.run(args);
        ctx.getBean(TeRunner.class).run();
    }
}
