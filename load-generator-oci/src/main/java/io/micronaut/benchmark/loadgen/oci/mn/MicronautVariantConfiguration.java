package io.micronaut.benchmark.loadgen.oci.mn;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@ConfigurationProperties("variants.micronaut")
public class MicronautVariantConfiguration {
    private List<Boolean> native_;
    private Map<String, List<String>> compileVariants;

    public Map<String, List<String>> getCompileVariants() {
        return compileVariants;
    }

    public void setCompileVariants(Map<String, List<String>> compileVariants) {
        this.compileVariants = compileVariants;
    }

    public List<Boolean> getNative() {
        return native_;
    }

    public void setNative(List<Boolean> native_) {
        this.native_ = native_;
    }
}
