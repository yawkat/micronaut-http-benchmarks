package io.micronaut.benchmark.loadgen.oci.techempower;

import io.micronaut.core.annotation.Nullable;

public record Revision(
        String githubRepoName,
        @Nullable
        String modulePrefix,
        String ref
) {
    static final String TEFB_NAME = "TechEmpower/FrameworkBenchmarks";

    String folderName() {
        return githubRepoName.replace('/', '_');
    }
}
