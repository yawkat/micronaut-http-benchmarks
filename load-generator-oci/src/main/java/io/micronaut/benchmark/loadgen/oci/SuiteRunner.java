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
import com.oracle.bmc.identity.model.CreateCompartmentDetails;
import com.oracle.bmc.identity.requests.CreateCompartmentRequest;
import com.oracle.bmc.identity.requests.DeleteCompartmentRequest;
import com.oracle.bmc.identity.requests.GetCompartmentRequest;
import com.oracle.bmc.identity.requests.ListCompartmentsRequest;
import com.oracle.bmc.identity.responses.CreateCompartmentResponse;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;

@Singleton
public class SuiteRunner {
    private static final Logger LOG = LoggerFactory.getLogger(SuiteRunner.class);

    private final IdentityClient identityClient;
    private final ComputeClient computeClient;
    private final VirtualNetworkClient vcnClient;
    private final GenericBenchmarkRunner benchmarkRunner;
    private final LoadManager loadManager;
    private final List<FrameworkRunSet> frameworks;
    private final ExecutorService executor;
    private final SuiteConfiguration suiteConfiguration;
    private final ObjectMapper objectMapper;
    private final Compute compute;

    public SuiteRunner(IdentityClient identityClient,
                       ComputeClient computeClient,
                       VirtualNetworkClient vcnClient,
                       GenericBenchmarkRunner benchmarkRunner,
                       LoadManager loadManager,
                       List<FrameworkRunSet> frameworks,
                       @Named(TaskExecutors.IO) ExecutorService executor,
                       SuiteConfiguration suiteConfiguration,
                       ObjectMapper objectMapper, Compute compute) {
        this.identityClient = identityClient;
        this.computeClient = computeClient;
        this.vcnClient = vcnClient;
        this.benchmarkRunner = benchmarkRunner;
        this.loadManager = loadManager;
        this.frameworks = frameworks;
        this.executor = executor;
        this.suiteConfiguration = suiteConfiguration;
        this.objectMapper = objectMapper;
        this.compute = compute;
    }

    public void clean() {

        cleanCompartment(suiteConfiguration.compartment, false);
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
        PhaseTracker phaseTracker = new PhaseTracker(objectMapper, outputDir);
        for (FrameworkRunSet framework : frameworks) {
            for (FrameworkRun run : framework.getRuns()) {
                if (!suiteConfiguration.enabledRunTypes.contains(run.type())) {
                    continue;
                }
                for (LoadVariant loadVariant : loadVariants) {
                    String name = run.name() + "-" + loadVariant.name();
                    index.add(new BenchmarkParameters(name, run.type(), run.parameters(), loadVariant));
                    PhaseTracker.PhaseUpdater phaseUpdater = phaseTracker.updater(name);
                    phaseUpdater.update(BenchmarkPhase.BEFORE);
                    allTasks.add(() -> MdcTracker.withMdc(name, () -> {
                        // we could use a child compartment here, but compartments seem to be heavily throttled
                        try {
                            benchmarkRunner.run(outputDir.resolve(name), new OciLocation(suiteConfiguration.compartment, suiteConfiguration.availabilityDomain), run, loadVariant, phaseUpdater::update);
                        } catch (Exception e) {
                            phaseUpdater.update(BenchmarkPhase.FAILED);
                            LOG.error("Failed to run benchmark", e);
                            executor.shutdownNow();
                        }
                        return null;
                    }));
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
        LOG.info("There are {} benchmarks to run", allTasks.size());
        Path newIndex = outputDir.resolve("index.new.json");
        objectMapper.writeValue(newIndex.toFile(), index);
        // use try-with-resources to clean the compartment
        try (CloseableCompartment ignored = new CloseableCompartment(suiteConfiguration.compartment, false)) {
            List<Future<Void>> futures = executor.invokeAll(allTasks);
            for (Future<Void> future : futures) {
                // any remaining errors
                future.get();
            }
        }
        progressTask.cancel(true);
        Files.move(newIndex, outputDir.resolve("index.json"), StandardCopyOption.REPLACE_EXISTING);
        LOG.info("All benchmarks complete");
        System.exit(0);
    }

    private CloseableCompartment createCompartment(String parent, String name) {
        Throttle.IDENTITY.takeUninterruptibly();
        CreateCompartmentResponse response = identityClient.createCompartment(CreateCompartmentRequest.builder()
                .createCompartmentDetails(CreateCompartmentDetails.builder()
                        .compartmentId(parent)
                        .name(name + "-" + ThreadLocalRandom.current().nextInt())
                        .description("Compartment for benchmark run " + name)
                        .build())
                .build());
        while (true) {
            Throttle.IDENTITY.takeUninterruptibly();
            Compartment.LifecycleState state = identityClient.getCompartment(GetCompartmentRequest.builder()
                    .compartmentId(response.getCompartment().getId())
                    .build()).getCompartment().getLifecycleState();
            if (state == Compartment.LifecycleState.Active) {
                break;
            } else if (state != Compartment.LifecycleState.Creating) {
                throw new IllegalStateException("Newly created compartment is already " + state);
            }
        }
        return new CloseableCompartment(response.getCompartment().getId(), true);
    }

    private void cleanCompartment(String compartment, boolean delete) {
        LOG.info("Cleaning compartment {}...", compartment);
        Throttle.IDENTITY.takeUninterruptibly();
        identityClient.listCompartments(ListCompartmentsRequest.builder()
                        .compartmentId(compartment)
                        .build())
                .getItems()
                .parallelStream()
                .filter(c -> c.getLifecycleState() == Compartment.LifecycleState.Active)
                .forEach(child -> cleanCompartment(child.getId(), true));

        Throttle.COMPUTE.takeUninterruptibly();
        for (Instance instance : list(
                computeClient::listInstances,
                ListInstancesRequest.builder()
                        .compartmentId(compartment),
                ListInstancesRequest.Builder::page,
                ListInstancesResponse::getOpcNextPage,
                ListInstancesResponse::getItems
        )) {
            Instance.LifecycleState lifecycleState = instance.getLifecycleState();
            if (lifecycleState != Instance.LifecycleState.Terminated && lifecycleState != Instance.LifecycleState.Terminating) {
                //noinspection resource
                compute.new Instance(instance.getId()).terminateAsync();
            }
        }

        Throttle.VCN.takeUninterruptibly();
        List<RouteTable> routeTables = list(
                vcnClient::listRouteTables,
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
                vcnClient.updateRouteTable(UpdateRouteTableRequest.builder()
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
                    computeClient::listInstances,
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
                vcnClient::listSubnets,
                ListSubnetsRequest.builder()
                        .compartmentId(compartment),
                ListSubnetsRequest.Builder::page,
                ListSubnetsResponse::getOpcNextPage,
                ListSubnetsResponse::getItems
        )) {
            LOG.info("Deleting subnet {}", subnet.getDisplayName());
            try {
                Throttle.VCN.takeUninterruptibly();
                vcnClient.deleteSubnet(DeleteSubnetRequest.builder()
                        .subnetId(subnet.getId())
                        .build());
            } catch (BmcException e) {
                LOG.warn("Failed to delete subnet: {}", e.getMessage());
            }
        }

        Throttle.VCN.takeUninterruptibly();
        List<Vcn> vcns = list(
                vcnClient::listVcns,
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
                    vcnClient.deleteRouteTable(DeleteRouteTableRequest.builder()
                            .rtId(routeTable.getId())
                            .build());
                } catch (BmcException e) {
                    LOG.warn("Failed to delete route table: {}", e.getMessage());
                }
            }
        }

        Throttle.VCN.takeUninterruptibly();
        for (InternetGateway ig : list(
                vcnClient::listInternetGateways,
                ListInternetGatewaysRequest.builder()
                        .compartmentId(compartment),
                ListInternetGatewaysRequest.Builder::page,
                ListInternetGatewaysResponse::getOpcNextPage,
                ListInternetGatewaysResponse::getItems
        )) {
            LOG.info("Deleting internet gateway {}", ig.getDisplayName());
            Throttle.VCN.takeUninterruptibly();
            vcnClient.deleteInternetGateway(DeleteInternetGatewayRequest.builder()
                    .igId(ig.getId())
                    .build());
        }

        Throttle.VCN.takeUninterruptibly();
        for (NatGateway nat : list(
                vcnClient::listNatGateways,
                ListNatGatewaysRequest.builder()
                        .compartmentId(compartment),
                ListNatGatewaysRequest.Builder::page,
                ListNatGatewaysResponse::getOpcNextPage,
                ListNatGatewaysResponse::getItems
        )) {
            LOG.info("Deleting NAT gateway {}", nat.getDisplayName());
            Throttle.VCN.takeUninterruptibly();
            vcnClient.deleteNatGateway(DeleteNatGatewayRequest.builder()
                    .natGatewayId(nat.getId())
                    .build());
        }

        for (Vcn vcn : vcns) {
            LOG.info("Deleting VCN {}", vcn.getDisplayName());
            try {
                Throttle.VCN.takeUninterruptibly();
                vcnClient.deleteVcn(DeleteVcnRequest.builder()
                        .vcnId(vcn.getId())
                        .build());
            } catch (BmcException e) {
                LOG.warn("Failed to delete VCN: {}", e.getMessage());
            }
        }

        if (delete) {
            LOG.info("Deleting compartment {}", compartment);
            Throttle.IDENTITY.takeUninterruptibly();
            identityClient.deleteCompartment(DeleteCompartmentRequest.builder()
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

    private final class CloseableCompartment implements AutoCloseable {
        private final String id;
        private final boolean deleteOnExit;

        CloseableCompartment(String id, boolean deleteOnExit) {
            this.id = id;
            this.deleteOnExit = deleteOnExit;
        }

        @Override
        public void close() {
            cleanCompartment(id, deleteOnExit);
        }
    }

    @ConfigurationProperties("suite")
    public static final class SuiteConfiguration {
        private String availabilityDomain;
        private String compartment;
        private List<String> enabledRunTypes;

        public String getCompartment() {
            return compartment;
        }

        public void setCompartment(String compartment) {
            this.compartment = compartment;
        }

        public String getAvailabilityDomain() {
            return availabilityDomain;
        }

        public void setAvailabilityDomain(String availabilityDomain) {
            this.availabilityDomain = availabilityDomain;
        }

        public List<String> getEnabledRunTypes() {
            return enabledRunTypes;
        }

        public void setEnabledRunTypes(List<String> enabledRunTypes) {
            this.enabledRunTypes = enabledRunTypes;
        }
    }

    public record BenchmarkParameters(
            String name,
            String type,
            Object parameters,
            LoadVariant load
    ) {
    }
}
