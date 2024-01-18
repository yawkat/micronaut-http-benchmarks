package io.micronaut.benchmark.loadgen.oci;

import com.oracle.bmc.common.RegionalClientBuilder;
import jakarta.inject.Singleton;

import java.util.List;

@Singleton
final class RegionCustomizer {
    private final SuiteRunner.SuiteConfiguration suiteConfiguration;
    private final List<RegionalClientBuilder<?, ?>> builders;

    RegionCustomizer(SuiteRunner.SuiteConfiguration suiteConfiguration, List<RegionalClientBuilder<?, ?>> builders) {
        this.suiteConfiguration = suiteConfiguration;
        this.builders = builders;
    }

    void setUp() {
        for (RegionalClientBuilder<?, ?> builder : builders) {
            builder.region(suiteConfiguration.region());
        }
    }
}
