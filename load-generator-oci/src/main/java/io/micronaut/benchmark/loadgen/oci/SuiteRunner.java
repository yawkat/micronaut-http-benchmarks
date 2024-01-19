package io.micronaut.benchmark.loadgen.oci;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.Instance;
import com.oracle.bmc.core.model.InternetGateway;
import com.oracle.bmc.core.model.NatGateway;
import com.oracle.bmc.core.model.RouteTable;
import com.oracle.bmc.core.model.Subnet;
import com.oracle.bmc.core.model.UpdateRouteTableDetails;
import com.oracle.bmc.core.model.Vcn;
import com.oracle.bmc.core.requests.DeleteInternetGatewayRequest;
import com.oracle.bmc.core.requests.DeleteNatGatewayRequest;
import com.oracle.bmc.core.requests.DeleteRouteTableRequest;
import com.oracle.bmc.core.requests.DeleteSubnetRequest;
import com.oracle.bmc.core.requests.DeleteVcnRequest;
import com.oracle.bmc.core.requests.ListInstancesRequest;
import com.oracle.bmc.core.requests.ListInternetGatewaysRequest;
import com.oracle.bmc.core.requests.ListNatGatewaysRequest;
import com.oracle.bmc.core.requests.ListRouteTablesRequest;
import com.oracle.bmc.core.requests.ListSubnetsRequest;
import com.oracle.bmc.core.requests.ListVcnsRequest;
import com.oracle.bmc.core.requests.UpdateRouteTableRequest;
import com.oracle.bmc.core.responses.ListInstancesResponse;
import com.oracle.bmc.core.responses.ListInternetGatewaysResponse;
import com.oracle.bmc.core.responses.ListNatGatewaysResponse;
import com.oracle.bmc.core.responses.ListRouteTablesResponse;
import com.oracle.bmc.core.responses.ListSubnetsResponse;
import com.oracle.bmc.core.responses.ListVcnsResponse;
import com.oracle.bmc.identity.IdentityClient;
import com.oracle.bmc.identity.model.Compartment;
import com.oracle.bmc.identity.requests.DeleteCompartmentRequest;
import com.oracle.bmc.identity.requests.ListCompartmentsRequest;
import com.oracle.bmc.model.BmcException;
import com.oracle.bmc.requests.BmcRequest;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.scheduling.TaskExecutors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;

@Singleton
public class SuiteRunner {
    private static final Logger LOG = LoggerFactory.getLogger(SuiteRunner.class);

    private final RegionalClient<IdentityClient> identityClient;
    private final RegionalClient<ComputeClient> computeClient;
    private final RegionalClient<VirtualNetworkClient> vcnClient;
    private final List<OciLocation> locations;
    private final Infrastructure.Factory infraFactory;
    private final LoadManager loadManager;
    private final List<FrameworkRunSet> frameworks;
    private final ExecutorService executor;
    private final SuiteConfiguration suiteConfiguration;
    private final ObjectMapper objectMapper;
    private final Compute compute;

    public SuiteRunner(RegionalClient<IdentityClient> identityClient,
                       RegionalClient<ComputeClient> computeClient,
                       RegionalClient<VirtualNetworkClient> vcnClient,
                       List<OciLocation> locations,
                       Infrastructure.Factory infraFactory,
                       LoadManager loadManager,
                       List<FrameworkRunSet> frameworks,
                       @Named(TaskExecutors.IO) ExecutorService executor,
                       SuiteConfiguration suiteConfiguration,
                       ObjectMapper objectMapper,
                       Compute compute) {
        this.identityClient = identityClient;
        this.computeClient = computeClient;
        this.vcnClient = vcnClient;
        this.locations = locations;
        this.infraFactory = infraFactory;
        this.loadManager = loadManager;
        this.frameworks = frameworks;
        this.executor = executor;
        this.suiteConfiguration = suiteConfiguration;
        this.objectMapper = objectMapper;
        this.compute = compute;
    }

    public void clean() {
        for (OciLocation location : locations) {
            cleanCompartment(location, false);
        }
    }

    public void run() throws Exception {
        Path outputDir = Path.of("output");
        try {
            Files.createDirectories(outputDir);
        } catch (FileAlreadyExistsException ignored) {}
        clean();

        List<LoadVariant> loadVariants = loadManager.getLoadVariants();
        List<Callable<Void>> allTasks = new ArrayList<>();
        List<BenchmarkParameters> index = new ArrayList<>();
        List<Infrastructure> sharedInfra = new ArrayList<>();
        PhaseTracker phaseTracker = new PhaseTracker(objectMapper, outputDir);
        Semaphore semaphore = new Semaphore(suiteConfiguration.maxConcurrentRuns);
        for (int repetition = 0; repetition < suiteConfiguration.repetitions; repetition++) {
            OciLocation location = locations.get(repetition % locations.size());
            Infrastructure repInfra;
            if (suiteConfiguration.infrastructureMode == InfrastructureMode.REUSE) {
                repInfra = infraFactory.create(location, outputDir.resolve("infra-" + repetition));
                sharedInfra.add(repInfra);
            } else {
                repInfra = null;
            }
            for (FrameworkRunSet framework : frameworks) {
                for (FrameworkRun run : framework.getRuns()) {
                    if (!suiteConfiguration.enabledRunTypes.contains(run.type())) {
                        continue;
                    }
                    for (LoadVariant loadVariant : loadVariants) {
                        String name = run.name() + "-" + loadVariant.name() + "-" + repetition;
                        index.add(new BenchmarkParameters(name, run.type(), run.parameters(), loadVariant, repetition));
                        PhaseTracker.PhaseUpdater phaseUpdater = phaseTracker.updater(name);
                        phaseUpdater.update(BenchmarkPhase.BEFORE);
                        Path out = outputDir.resolve(name);
                        allTasks.add(() -> {
                            MdcTracker.withMdc(name, () -> {
                                // we could use a child compartment here, but compartments seem to be heavily throttled
                                try {
                                    if (suiteConfiguration.infrastructureMode == InfrastructureMode.REUSE) {
                                        assert repInfra != null;
                                        repInfra.run(out, run, loadVariant, phaseUpdater);
                                        phaseUpdater.update(BenchmarkPhase.DONE);
                                    } else {
                                        semaphore.acquire();
                                        try (Infrastructure infra = infraFactory.create(location, out)) {
                                            infra.run(out, run, loadVariant, phaseUpdater);
                                            phaseUpdater.update(BenchmarkPhase.SHUTTING_DOWN);
                                        }
                                        phaseUpdater.update(BenchmarkPhase.DONE);
                                        semaphore.release();
                                    }
                                } catch (Exception e) {
                                    phaseUpdater.update(BenchmarkPhase.FAILED);
                                    LOG.error("Failed to run benchmark", e);
                                    executor.shutdownNow();
                                }
                                return null;
                            });
                            return null;
                        });
                    }
                }
            }
        }
        Future<?> progressTask = executor.submit(() -> {
            try {
                phaseTracker.trackLoop();
            } catch (IOException e) {
                LOG.error("Error in phase tracker", e);
            }
        });
        Collections.shuffle(allTasks);
        LOG.info("There are {} benchmarks to run", allTasks.size());
        Path newIndex = outputDir.resolve("index.new.json");
        objectMapper.writeValue(newIndex.toFile(), index);
        try {
            List<Future<Void>> futures = executor.invokeAll(allTasks);
            for (Future<Void> future : futures) {
                // any remaining errors
                future.get();
            }
        } finally {
            for (Infrastructure infrastructure : sharedInfra) {
                try {
                    infrastructure.close();
                } catch (Exception e) {
                    LOG.error("Failed to close shared infrastructure", e);
                }
            }
            for (OciLocation location : locations) {
                cleanCompartment(location, false);
            }
        }
        progressTask.cancel(true);
        Files.move(newIndex, outputDir.resolve("index.json"), StandardCopyOption.REPLACE_EXISTING);
        LOG.info("All benchmarks complete");
        System.exit(0);
    }

    private void cleanCompartment(OciLocation location, boolean delete) {
        String compartment = location.compartmentId();
        LOG.info("Cleaning compartment {} in region {}...", compartment, location.region());
        Throttle.IDENTITY.takeUninterruptibly();
        identityClient.forRegion(location).listCompartments(ListCompartmentsRequest.builder()
                        .compartmentId(compartment)
                        .build())
                .getItems()
                .parallelStream()
                .filter(c -> c.getLifecycleState() == Compartment.LifecycleState.Active)
                .forEach(child -> cleanCompartment(new OciLocation(child.getId(), location.region(), location.availabilityDomain()), true));

        Throttle.COMPUTE.takeUninterruptibly();
        for (Instance instance : list(
                computeClient.forRegion(location)::listInstances,
                ListInstancesRequest.builder()
                        .compartmentId(compartment),
                ListInstancesRequest.Builder::page,
                ListInstancesResponse::getOpcNextPage,
                ListInstancesResponse::getItems
        )) {
            Instance.LifecycleState lifecycleState = instance.getLifecycleState();
            if (lifecycleState != Instance.LifecycleState.Terminated && lifecycleState != Instance.LifecycleState.Terminating) {
                //noinspection resource
                compute.new Instance(location, instance.getId()).terminateAsync();
            }
        }

        Throttle.VCN.takeUninterruptibly();
        List<RouteTable> routeTables = list(
                vcnClient.forRegion(location)::listRouteTables,
                ListRouteTablesRequest.builder()
                        .compartmentId(compartment),
                ListRouteTablesRequest.Builder::page,
                ListRouteTablesResponse::getOpcNextPage,
                ListRouteTablesResponse::getItems
        );
        for (RouteTable routeTable : routeTables) {
            if (!routeTable.getRouteRules().isEmpty()) {
                LOG.info("Clearing route table {}", routeTable.getDisplayName());
                Throttle.VCN.takeUninterruptibly();
                vcnClient.forRegion(location).updateRouteTable(UpdateRouteTableRequest.builder()
                        .rtId(routeTable.getId())
                        .updateRouteTableDetails(UpdateRouteTableDetails.builder()
                                .routeRules(Collections.emptyList())
                                .build())
                        .build());
            }
        }

        while (true) {
            Throttle.COMPUTE.takeUninterruptibly();
            List<Instance> instances = list(
                    computeClient.forRegion(location)::listInstances,
                    ListInstancesRequest.builder()
                            .compartmentId(compartment),
                    ListInstancesRequest.Builder::page,
                    ListInstancesResponse::getOpcNextPage,
                    ListInstancesResponse::getItems
            );
            int terminating = 0;
            for (Instance instance : instances) {
                switch (instance.getLifecycleState()) {
                    case Terminating -> terminating++;
                    case Terminated -> {} // fine
                    default -> throw new IllegalStateException("Unexpected state for compute instance " + instance.getId() + " " + instance.getLifecycleState() + ". All instances should be terminating.");
                }
            }
            if (terminating == 0) {
                break;
            }
            LOG.info("Waiting for {} instances to terminate", terminating);
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        Throttle.VCN.takeUninterruptibly();
        for (Subnet subnet : list(
                vcnClient.forRegion(location)::listSubnets,
                ListSubnetsRequest.builder()
                        .compartmentId(compartment),
                ListSubnetsRequest.Builder::page,
                ListSubnetsResponse::getOpcNextPage,
                ListSubnetsResponse::getItems
        )) {
            LOG.info("Deleting subnet {}", subnet.getDisplayName());
            try {
                Throttle.VCN.takeUninterruptibly();
                vcnClient.forRegion(location).deleteSubnet(DeleteSubnetRequest.builder()
                        .subnetId(subnet.getId())
                        .build());
            } catch (BmcException e) {
                LOG.warn("Failed to delete subnet: {}", e.getMessage());
            }
        }

        Throttle.VCN.takeUninterruptibly();
        List<Vcn> vcns = list(
                vcnClient.forRegion(location)::listVcns,
                ListVcnsRequest.builder()
                        .compartmentId(compartment),
                ListVcnsRequest.Builder::page,
                ListVcnsResponse::getOpcNextPage,
                ListVcnsResponse::getItems
        );

        for (RouteTable routeTable : routeTables) {
            if (vcns.stream().noneMatch(vcn -> vcn.getDefaultRouteTableId().equals(routeTable.getId()))) {
                LOG.info("Deleting route table {}", routeTable.getDisplayName());
                try {
                    Throttle.VCN.takeUninterruptibly();
                    vcnClient.forRegion(location).deleteRouteTable(DeleteRouteTableRequest.builder()
                            .rtId(routeTable.getId())
                            .build());
                } catch (BmcException e) {
                    LOG.warn("Failed to delete route table: {}", e.getMessage());
                }
            }
        }

        Throttle.VCN.takeUninterruptibly();
        for (InternetGateway ig : list(
                vcnClient.forRegion(location)::listInternetGateways,
                ListInternetGatewaysRequest.builder()
                        .compartmentId(compartment),
                ListInternetGatewaysRequest.Builder::page,
                ListInternetGatewaysResponse::getOpcNextPage,
                ListInternetGatewaysResponse::getItems
        )) {
            LOG.info("Deleting internet gateway {}", ig.getDisplayName());
            Throttle.VCN.takeUninterruptibly();
            vcnClient.forRegion(location).deleteInternetGateway(DeleteInternetGatewayRequest.builder()
                    .igId(ig.getId())
                    .build());
        }

        Throttle.VCN.takeUninterruptibly();
        for (NatGateway nat : list(
                vcnClient.forRegion(location)::listNatGateways,
                ListNatGatewaysRequest.builder()
                        .compartmentId(compartment),
                ListNatGatewaysRequest.Builder::page,
                ListNatGatewaysResponse::getOpcNextPage,
                ListNatGatewaysResponse::getItems
        )) {
            LOG.info("Deleting NAT gateway {}", nat.getDisplayName());
            Throttle.VCN.takeUninterruptibly();
            vcnClient.forRegion(location).deleteNatGateway(DeleteNatGatewayRequest.builder()
                    .natGatewayId(nat.getId())
                    .build());
        }

        for (Vcn vcn : vcns) {
            LOG.info("Deleting VCN {}", vcn.getDisplayName());
            try {
                Throttle.VCN.takeUninterruptibly();
                vcnClient.forRegion(location).deleteVcn(DeleteVcnRequest.builder()
                        .vcnId(vcn.getId())
                        .build());
            } catch (BmcException e) {
                LOG.warn("Failed to delete VCN: {}", e.getMessage());
            }
        }

        if (delete) {
            LOG.info("Deleting compartment {}", compartment);
            Throttle.IDENTITY.takeUninterruptibly();
            identityClient.forRegion(location).deleteCompartment(DeleteCompartmentRequest.builder()
                    .compartmentId(compartment)
                    .build());
        }
    }

    private <T, BUILDER extends BmcRequest.Builder<REQ, ?>, REQ extends BmcRequest<?>, RESP> List<T> list(
            Function<REQ, RESP> call,
            BUILDER builder,
            BiConsumer<BUILDER, String> setPage,
            Function<RESP, String> getNextPage,
            Function<RESP, List<T>> getItems
    ) {
        List<T> result = new ArrayList<>();
        while (true) {
            REQ req = builder.build();
            RESP resp = call.apply(req);
            result.addAll(getItems.apply(resp));
            String nextPage = getNextPage.apply(resp);
            if (nextPage == null) {
                break;
            } else {
                setPage.accept(builder, nextPage);
            }
        }
        return result;
    }

    @ConfigurationProperties("suite")
    public record SuiteConfiguration(
            List<String> enabledRunTypes,
            int repetitions,
            int maxConcurrentRuns,
            InfrastructureMode infrastructureMode
    ) {
    }

    public enum InfrastructureMode {
        /**
         * Set up a new infrastructure for each run. Very parallelizable, somewhat wasteful (infra setup takes time),
         * more prone to infra differences between runs.
         */
        INFRASTRUCTURE_PER_RUN,
        /**
         * Use one infrastructure per repetition and run every framework config on it. Only as parallel as
         * {@link SuiteConfiguration#repetitions}, but less prone to bias between runs.
         */
        REUSE
    }

    public record BenchmarkParameters(
            String name,
            String type,
            Object parameters,
            LoadVariant load,
            int repetition
    ) {
    }
}
