package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@ConfigurationProperties("variants.native-image")
public class NativeImageConfiguration {
    private List<String> optionChoices;
    private Map<String, String> prefixOptions;

    public Map<String, String> getPrefixOptions() {
        return prefixOptions;
    }

    public void setPrefixOptions(Map<String, String> prefixOptions) {
        this.prefixOptions = prefixOptions;
    }

    public List<String> getOptionChoices() {
        return optionChoices;
    }

    public void setOptionChoices(List<String> optionChoices) {
        this.optionChoices = optionChoices;
    }
}
