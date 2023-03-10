package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("variants.hotspot")
public class HotspotConfiguration {
    private List<String> optionChoices;

    public List<String> getOptionChoices() {
        return optionChoices;
    }

    public void setOptionChoices(List<String> optionChoices) {
        this.optionChoices = optionChoices;
    }
}