package io.micronaut.benchmark.loadgen.oci;

import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.apache.sshd.client.session.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

@Singleton
public class Infrastructure extends AbstractInfrastructure {
    private static final Logger LOG = LoggerFactory.getLogger(Infrastructure.class);

    static final String SERVER_IP = "10.0.0.2";

    private final Factory factory;

    private Compute.Instance benchmarkServer;
    private HyperfoilRunner hyperfoilRunner;

    private boolean started;
    private boolean stopped;

    private Infrastructure(Factory factory, OciLocation location, Path logDirectory) {
        super(location, logDirectory, factory.vcnClient, factory.computeClient, factory.compute);
        this.factory = factory;
    }

    private void start(PhaseTracker.PhaseUpdater progress) throws Exception {
        setupBase(progress);

        benchmarkServer = factory.compute.builder("benchmark-server", location, privateSubnetId)
                .privateIp(SERVER_IP)
                .launch();
        hyperfoilRunner = factory.hyperfoilRunnerFactory.launch(logDirectory, location, privateSubnetId);
        hyperfoilRunner.setRelay(relay());

        benchmarkServer.awaitStartup();

        try (ClientSession benchmarkServerClient = factory.sshFactory.connect(benchmarkServer, SERVER_IP, relay());
             OutputListener.Write log = new OutputListener.Write(Files.newOutputStream(logDirectory.resolve("update.log")))) {

            progress.update(BenchmarkPhase.DEPLOYING_OS);
            LOG.info("Updating benchmark server");
            SshUtil.openFirewallPorts(benchmarkServerClient, log);
            // this takes too long
            //SshUtil.run(benchmarkServerClient, "sudo yum update -y", log);
        }

        started = true;
    }

    @Override
    public void close() throws Exception {
        stopped = true;

        // terminate asynchronously. we will wait for termination in close()
        if (hyperfoilRunner != null) {
            hyperfoilRunner.terminateAsync();
        }
        if (benchmarkServer != null) {
            benchmarkServer.terminateAsync();
        }

        if (hyperfoilRunner != null) {
            hyperfoilRunner.close();
        }
        terminateRelayAsync();
        if (benchmarkServer != null) {
            benchmarkServer.close();
        }

        super.close();
    }

    public synchronized void run(Path outputDirectory, FrameworkRun run, LoadVariant loadVariant, PhaseTracker.PhaseUpdater progress) throws Exception {
        if (stopped) {
            throw new InterruptedException("Already stopped");
        }
        try {
            if (!started) {
                retry(() -> {
                    start(progress);
                    return null;
                });
            }

            try {
                Files.createDirectories(outputDirectory);
            } catch (FileAlreadyExistsException ignored) {
            }

            retry(() -> {
                try {
                    run0(outputDirectory, run, loadVariant, progress);
                } catch (Exception e) {
                    LOG.error("Benchmark run failed, may retry", e);
                    throw e;
                }
                return null;
            });
        } catch (Exception e) {
            // prevent reuse
            stopped = true;
            throw e;
        }
    }

    private void run0(Path outputDirectory, FrameworkRun run, LoadVariant loadVariant, PhaseTracker.PhaseUpdater progress) throws Exception {
        try (ClientSession benchmarkServerClient = factory.sshFactory.connect(benchmarkServer, SERVER_IP, relay());
             OutputListener.Write log = new OutputListener.Write(Files.newOutputStream(outputDirectory.resolve("server.log")))) {
            // special PhaseUpdater that logs the current benchmark phase for reference.
            progress = new PhaseTracker.DelegatePhaseUpdater(progress) {
                String lastDisplay = null;

                @Override
                public void update(BenchmarkPhase phase, double percent, @Nullable String displayProgress) {
                    if (!Objects.equals(displayProgress, lastDisplay)) {
                        log.println("----------------- Benchmark progress changed to: " + displayProgress);
                        lastDisplay = displayProgress;
                    }
                    super.update(phase, percent, displayProgress);
                }
            };

            PhaseTracker.PhaseUpdater finalProgress = progress;
            factory.sutMonitor.monitorAndRun(
                    benchmarkServerClient,
                    outputDirectory,
                    () -> {
                        run.setupAndRun(
                                benchmarkServerClient,
                                outputDirectory,
                                log,
                                hyperfoilRunner.benchmarkClosure(outputDirectory, loadVariant.protocol(), loadVariant.body()),
                                finalProgress);
                        return null;
                    }
            );
        }
    }

    @Singleton
    public record Factory(
            RegionalClient<ComputeClient> computeClient,
            RegionalClient<VirtualNetworkClient> vcnClient,
            Compute compute,
            HyperfoilRunner.Factory hyperfoilRunnerFactory,
            SshFactory sshFactory,
            SutMonitor sutMonitor
    ) {
        Infrastructure create(OciLocation location, Path logDirectory) {
            return new Infrastructure(this, location, logDirectory);
        }
    }
}
