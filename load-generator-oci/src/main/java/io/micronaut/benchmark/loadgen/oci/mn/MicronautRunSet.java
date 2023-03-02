package io.micronaut.benchmark.loadgen.oci.mn;

import io.micronaut.benchmark.loadgen.oci.FrameworkRun;
import io.micronaut.benchmark.loadgen.oci.FrameworkRunSet;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Singleton
public class MicronautRunSet implements FrameworkRunSet {
    private final MicronautVariantConfiguration variantConfiguration;

    public MicronautRunSet(MicronautVariantConfiguration variantConfiguration) {
        this.variantConfiguration = variantConfiguration;
    }

    @Override
    public List<? extends FrameworkRun> getRuns() {
        return MicronautRunSet.cartesianProduct(variantConfiguration.getCompileVariants())
                .stream()
                .map(MicronautHotspotRun::new)
                .toList();
    }

    private static <K, V> List<Map<K, V>> cartesianProduct(Map<? extends K, ? extends List<? extends V>> map) {
        List<Map<K, V>> result = List.of(Map.of());
        for (Map.Entry<? extends K, ? extends List<? extends V>> dimension : map.entrySet()) {
            List<Map<K, V>> next = new ArrayList<>();
            for (V value : dimension.getValue()) {
                for (Map<K, V> previousMap : result) {
                    Map<K, V> joined = new LinkedHashMap<>(previousMap);
                    joined.put(dimension.getKey(), value);
                    next.add(joined);
                }
            }
            result = next;
        }
        return result;
    }
}
