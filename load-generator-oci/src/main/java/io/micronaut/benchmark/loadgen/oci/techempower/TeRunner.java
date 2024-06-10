package io.micronaut.benchmark.loadgen.oci.techempower;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.benchmark.loadgen.oci.CompartmentCleaner;
import io.micronaut.benchmark.loadgen.oci.OciLocation;
import io.micronaut.benchmark.loadgen.oci.PhaseTracker;
import jakarta.inject.Singleton;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Singleton
public class TeRunner {
    private final CompartmentCleaner compartmentCleaner;
    private final TeConfiguration configuration;
    private final TeInfrastructure.Factory infrastructureFactory;
    private final ObjectMapper objectMapper;

    public TeRunner(
            CompartmentCleaner compartmentCleaner,
            TeConfiguration configuration, TeInfrastructure.Factory infrastructureFactory, ObjectMapper objectMapper
    ) {
        this.compartmentCleaner = compartmentCleaner;
        this.configuration = configuration;
        this.infrastructureFactory = infrastructureFactory;
        this.objectMapper = objectMapper;
    }

    public void run() throws Exception {
        OciLocation location = new OciLocation(configuration.compartmentId(), configuration.region(), configuration.availabilityDomain());

        Path outputDir = Path.of("techempower-output");
        try {
            Files.createDirectories(outputDir);
        } catch (FileAlreadyExistsException ignored) {
        }

        compartmentCleaner.cleanCompartment(location, false);

        PhaseTracker phaseTracker = new PhaseTracker(objectMapper, outputDir);

        try (TeInfrastructure infrastructure = infrastructureFactory.create(location, outputDir.resolve("infra"))) {
            PhaseTracker.PhaseUpdater main = phaseTracker.updater("main");
            infrastructure.start(main);
            infrastructure.run(outputDir, List.of(
                    new Revision(Revision.TEFB_NAME, null, "master")
                    //new Revision("micronaut-projects/micronaut-core", "io.micronaut:micronaut-", "4.5.x")
            ));
        }
        System.exit(0);
    }
}
