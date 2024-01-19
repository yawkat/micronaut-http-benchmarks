package io.micronaut.benchmark.loadgen.oci;

import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.core.model.CreateInternetGatewayDetails;
import com.oracle.bmc.core.model.CreateNatGatewayDetails;
import com.oracle.bmc.core.model.CreateRouteTableDetails;
import com.oracle.bmc.core.model.CreateSubnetDetails;
import com.oracle.bmc.core.model.CreateVcnDetails;
import com.oracle.bmc.core.model.IngressSecurityRule;
import com.oracle.bmc.core.model.RouteRule;
import com.oracle.bmc.core.model.SecurityList;
import com.oracle.bmc.core.model.UpdateSecurityListDetails;
import com.oracle.bmc.core.model.Vcn;
import com.oracle.bmc.core.requests.CreateInternetGatewayRequest;
import com.oracle.bmc.core.requests.CreateNatGatewayRequest;
import com.oracle.bmc.core.requests.CreateRouteTableRequest;
import com.oracle.bmc.core.requests.CreateSubnetRequest;
import com.oracle.bmc.core.requests.CreateVcnRequest;
import com.oracle.bmc.core.requests.DeleteInternetGatewayRequest;
import com.oracle.bmc.core.requests.DeleteNatGatewayRequest;
import com.oracle.bmc.core.requests.DeleteRouteTableRequest;
import com.oracle.bmc.core.requests.DeleteSubnetRequest;
import com.oracle.bmc.core.requests.DeleteVcnRequest;
import com.oracle.bmc.core.requests.GetSecurityListRequest;
import com.oracle.bmc.core.requests.GetVnicRequest;
import com.oracle.bmc.core.requests.ListVnicAttachmentsRequest;
import com.oracle.bmc.core.requests.UpdateSecurityListRequest;
import com.oracle.bmc.model.BmcException;
import io.netty.channel.ConnectTimeoutException;
import jakarta.inject.Singleton;
import org.apache.sshd.client.session.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

@Singleton
public class Infrastructure implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(Infrastructure.class);

    private static final String NETWORK = "10.0.0.0/16";
    private static final String PRIVATE_SUBNET = "10.0.0.0/18";
    private static final String RELAY_SUBNET = "10.0.254.0/24";
    static final String SERVER_IP = "10.0.0.2";

    private final Factory factory;
    private final OciLocation location;
    private final Path logDirectory;

    private String publicSubnetId;
    private String privateSubnetId;
    private String privateRouteTable;
    private String publicRouteTable;
    private String vcnId;
    private String natId;
    private String internetId;
    private Compute.Instance benchmarkServer;
    private RelayServer relayServer;
    private HyperfoilRunner hyperfoilRunner;
    private SshFactory.Relay relay;

    private boolean started;
    private boolean stopped;

    private Infrastructure(Factory factory, OciLocation location, Path logDirectory) {
        this.factory = factory;
        this.location = location;
        this.logDirectory = logDirectory;
    }

    private void start(PhaseTracker.PhaseUpdater progress) throws Exception {
        progress.update(BenchmarkPhase.CREATING_VCN);
        Vcn vcn = createVcn(location);
        progress.update(BenchmarkPhase.SETTING_UP_NETWORK);
        vcnId = vcn.getId();
        Throttle.VCN.take();
        natId = factory.vcnClient.forRegion(location).createNatGateway(CreateNatGatewayRequest.builder()
                .createNatGatewayDetails(CreateNatGatewayDetails.builder()
                        .compartmentId(location.compartmentId())
                        .vcnId(vcnId)
                        .displayName("NAT Gateway")
                        .build())
                .build()).getNatGateway().getId();
        Throttle.VCN.take();
        internetId = factory.vcnClient.forRegion(location).createInternetGateway(CreateInternetGatewayRequest.builder()
                .createInternetGatewayDetails(CreateInternetGatewayDetails.builder()
                        .compartmentId(location.compartmentId())
                        .vcnId(vcnId)
                        .displayName("Internet Gateway")
                        .isEnabled(true)
                        .build())
                .build()).getInternetGateway().getId();
        Throttle.VCN.take();
        privateRouteTable = factory.vcnClient.forRegion(location).createRouteTable(CreateRouteTableRequest.builder()
                .createRouteTableDetails(CreateRouteTableDetails.builder()
                        .compartmentId(location.compartmentId())
                        .vcnId(vcnId)
                        .routeRules(List.of(RouteRule.builder()
                                .destinationType(RouteRule.DestinationType.CidrBlock)
                                .destination("0.0.0.0/0")
                                .networkEntityId(natId)
                                .routeType(RouteRule.RouteType.Static)
                                .build()))
                        .build())
                .build()).getRouteTable().getId();
        Throttle.VCN.take();
        publicRouteTable = factory.vcnClient.forRegion(location).createRouteTable(CreateRouteTableRequest.builder()
                .createRouteTableDetails(CreateRouteTableDetails.builder()
                        .compartmentId(location.compartmentId())
                        .vcnId(vcnId)
                        .routeRules(List.of(RouteRule.builder()
                                .destinationType(RouteRule.DestinationType.CidrBlock)
                                .destination("0.0.0.0/0")
                                .networkEntityId(internetId)
                                .routeType(RouteRule.RouteType.Static)
                                .build()))
                        .build())
                .build()).getRouteTable().getId();
        Throttle.VCN.take();
        privateSubnetId = factory.vcnClient.forRegion(location).createSubnet(CreateSubnetRequest.builder()
                .createSubnetDetails(CreateSubnetDetails.builder()
                        .compartmentId(location.compartmentId())
                        .vcnId(vcnId)
                        .displayName("Private subnet")
                        .cidrBlock(PRIVATE_SUBNET)
                        .routeTableId(privateRouteTable)
                        .availabilityDomain(location.availabilityDomain())
                        .build())
                .build()).getSubnet().getId();
        Throttle.VCN.take();
        publicSubnetId = factory.vcnClient.forRegion(location).createSubnet(CreateSubnetRequest.builder()
                .createSubnetDetails(CreateSubnetDetails.builder()
                        .compartmentId(location.compartmentId())
                        .vcnId(vcnId)
                        .displayName("Relay subnet")
                        .cidrBlock(RELAY_SUBNET)
                        .routeTableId(publicRouteTable)
                        .availabilityDomain(location.availabilityDomain())
                        .build())
                .build()).getSubnet().getId();

        Throttle.VCN.take();
        SecurityList securityList = retry(() -> factory.vcnClient.forRegion(location).getSecurityList(GetSecurityListRequest.builder()
                .securityListId(vcn.getDefaultSecurityListId())
                .build()).getSecurityList());
        List<IngressSecurityRule> ingressRules = new ArrayList<>(securityList.getIngressSecurityRules());
        // allow all internal traffic
        ingressRules.add(IngressSecurityRule.builder()
                .source("10.0.0.0/8")
                .sourceType(IngressSecurityRule.SourceType.CidrBlock)
                .protocol("all")
                .isStateless(true)
                .build());
        retry(() -> {
            Throttle.VCN.take();
            factory.vcnClient.forRegion(location).updateSecurityList(UpdateSecurityListRequest.builder()
                    .securityListId(vcn.getDefaultSecurityListId())
                    .updateSecurityListDetails(UpdateSecurityListDetails.builder()
                            .ingressSecurityRules(ingressRules)
                            .build())
                    .build());
            return null;
        });

        progress.update(BenchmarkPhase.SETTING_UP_INSTANCES);

        benchmarkServer = factory.compute.builder("benchmark-server", location, privateSubnetId)
                .privateIp(SERVER_IP)
                .launch();
        hyperfoilRunner = factory.hyperfoilRunnerFactory.launch(logDirectory, location, privateSubnetId);
        relayServer = createRelayServer(location, publicSubnetId);

        relay = new SshFactory.Relay("opc", relayServer.publicIp);
        hyperfoilRunner.setRelay(relay);

        benchmarkServer.awaitStartup();

        try (ClientSession benchmarkServerClient = factory.sshFactory.connect(benchmarkServer, SERVER_IP, relay);
             OutputListener.Write log = new OutputListener.Write(Files.newOutputStream(logDirectory.resolve("update.log")))) {

            progress.update(BenchmarkPhase.DEPLOYING_OS);
            LOG.info("Updating benchmark server");
            SshUtil.openFirewallPorts(benchmarkServerClient, log);
            // this takes too long
            SshUtil.run(benchmarkServerClient, "sudo yum update -y", log);
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
        if (relayServer != null) {
            relayServer.instance.terminateAsync();
        }
        if (relayServer != null) {
            relayServer.close();
        }
        if (benchmarkServer != null) {
            benchmarkServer.close();
        }

        LOG.info("Terminating network resources");
        try {
            for (String subnet : new String[]{privateSubnetId, publicSubnetId}) {
                if (subnet != null) {
                    retry(() -> {
                        Throttle.VCN.takeUninterruptibly();
                        return factory.vcnClient.forRegion(location).deleteSubnet(DeleteSubnetRequest.builder().subnetId(subnet).build());
                    });
                }
            }
            for (String routeTable : new String[]{privateRouteTable, publicRouteTable}) {
                if (routeTable != null) {
                    retry(() -> {
                        Throttle.VCN.takeUninterruptibly();
                        return factory.vcnClient.forRegion(location).deleteRouteTable(DeleteRouteTableRequest.builder().rtId(routeTable).build());
                    });
                }
            }
            if (internetId != null) {
                retry(() -> {
                    Throttle.VCN.takeUninterruptibly();
                    return factory.vcnClient.forRegion(location).deleteInternetGateway(DeleteInternetGatewayRequest.builder().igId(internetId).build());
                });
            }
            if (natId != null) {
                retry(() -> {
                    Throttle.VCN.takeUninterruptibly();
                    return factory.vcnClient.forRegion(location).deleteNatGateway(DeleteNatGatewayRequest.builder().natGatewayId(natId).build());
                });
            }
            if (vcnId != null) {
                retry(() -> {
                    Throttle.VCN.takeUninterruptibly();
                    return factory.vcnClient.forRegion(location).deleteVcn(DeleteVcnRequest.builder().vcnId(vcnId).build());
                });
            }
        } catch (BmcException e) {
            LOG.warn("Failed to terminate benchmark network. Cleanup will happen after all benchmarks complete.", e);
        }
    }

    public synchronized void run(Path outputDirectory, FrameworkRun run, LoadVariant loadVariant, PhaseTracker.PhaseUpdater progress) throws Exception {
        if (stopped) {
            throw new IllegalStateException("Already stopped");
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
        try (ClientSession benchmarkServerClient = factory.sshFactory.connect(benchmarkServer, SERVER_IP, relay);
             OutputListener.Write log = new OutputListener.Write(Files.newOutputStream(outputDirectory.resolve("server.log")))) {
            run.setupAndRun(
                    benchmarkServerClient,
                    outputDirectory,
                    log,
                    hyperfoilRunner.benchmarkClosure(outputDirectory, loadVariant.protocol(), loadVariant.body()),
                    progress);
        }
    }

    private Vcn createVcn(OciLocation location) throws InterruptedException {
        Vcn vcn;
        while (true) {
            try {
                Throttle.VCN.take();
                vcn = factory.vcnClient.forRegion(location).createVcn(CreateVcnRequest.builder()
                        .createVcnDetails(CreateVcnDetails.builder()
                                .compartmentId(location.compartmentId())
                                .displayName("Benchmark network")
                                .cidrBlock(NETWORK)
                                .build())
                        .build()).getVcn();
                break;
            } catch (BmcException be) {
                if (be.getCause() instanceof ConnectTimeoutException) {
                    TimeUnit.SECONDS.sleep(10);
                    continue;
                }
                if (be.getStatusCode() == 400 && "LimitExceeded".equals(be.getServiceCode())) {
                    LOG.warn("Hit limit in CreateVcn operation. Likely you need to up your vcn-count limit. Waiting for 2m.");
                    TimeUnit.MINUTES.sleep(2);
                    continue;
                }
                if (be.getStatusCode() == 429 && "TooManyRequests".equals(be.getServiceCode())) {
                    TimeUnit.MINUTES.sleep(1);
                    continue;
                }
                throw be;
            }
        }
        return vcn;
    }

    static <T> T retry(Callable<T> callable) throws Exception {
        return retry(callable, () -> {});
    }

    static <T> T retry(Callable<T> callable, Runnable onFailure) throws Exception {
        Exception err = null;
        for (int i = 0; i < 3; i++) {
            try {
                return callable.call();
            } catch (InvalidatesBenchmarkException ibe) {
                throw ibe;
            } catch (Exception e) {
                if (err == null) {
                    err = e;
                } else {
                    err.addSuppressed(e);
                }
            }
            onFailure.run();
            TimeUnit.SECONDS.sleep(10);
        }
        throw err;
    }

    private RelayServer createRelayServer(OciLocation location, String subnetId) throws InterruptedException {
        LOG.info("Creating relay server");
        Compute.Instance relayServerInstance = factory.compute.builder("relay-server", location, subnetId)
                .publicIp(true)
                .launch();

        relayServerInstance.awaitStartup();

        Throttle.COMPUTE.take();
        String vnic = factory.computeClient.forRegion(location).listVnicAttachments(ListVnicAttachmentsRequest.builder()
                .compartmentId(location.compartmentId())
                .availabilityDomain(location.availabilityDomain())
                .instanceId(relayServerInstance.id)
                .build()).getItems().get(0).getVnicId();
        String publicIp = factory.vcnClient.forRegion(location).getVnic(GetVnicRequest.builder()
                .vnicId(vnic)
                .build()).getVnic().getPublicIp();
        return new RelayServer(relayServerInstance, publicIp);
    }

    private record RelayServer(
            Compute.Instance instance,
            String publicIp
    ) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            instance.close();
        }
    }

    @Singleton
    public record Factory(
            RegionalClient<ComputeClient> computeClient,
            RegionalClient<VirtualNetworkClient> vcnClient,
            Compute compute,
            HyperfoilRunner.Factory hyperfoilRunnerFactory,
            SshFactory sshFactory
    ) {
        Infrastructure create(OciLocation location, Path logDirectory) {
            return new Infrastructure(this, location, logDirectory);
        }
    }
}
