package io.micronaut.benchmark.loadgen.oci.mn;

import io.micronaut.benchmark.loadgen.oci.FrameworkRun;
import io.micronaut.benchmark.loadgen.oci.FrameworkRunSet;
import io.micronaut.benchmark.loadgen.oci.JavaRunFactory;
import jakarta.inject.Singleton;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Singleton
public class MicronautRunSet implements FrameworkRunSet {
    private final MicronautVariantConfiguration variantConfiguration;
    private final JavaRunFactory javaRunFactory;

    public MicronautRunSet(MicronautVariantConfiguration variantConfiguration, JavaRunFactory javaRunFactory) {
        this.variantConfiguration = variantConfiguration;
        this.javaRunFactory = javaRunFactory;
    }

    @Override
    public List<? extends FrameworkRun> getRuns() {
        return MicronautRunSet.cartesianProduct(variantConfiguration.compileVariants())
                .stream()
                .flatMap(compileConfiguration -> javaRunFactory.createJavaRuns("mn")
                        .shadowJar(Path.of("build/libs", variantName(compileConfiguration) + "-all.jar"))
                        .configString(variantName(compileConfiguration))
                        .compileConfiguration(compileConfiguration)
                        .boundOn("io.micronaut.runtime.Micronaut - Startup completed")
                        .build()
                )
                .toList();
    }

    private static String variantName(Map<String, String> compileConfiguration) {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> e : compileConfiguration.entrySet()) {
            builder.append(e.getKey()).append('-').append(e.getValue()).append('-');
        }
        builder.setLength(builder.length() - 1); // remove trailing dash
        return builder.toString();
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
