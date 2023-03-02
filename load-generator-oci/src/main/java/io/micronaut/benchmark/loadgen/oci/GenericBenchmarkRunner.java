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
import com.oracle.bmc.core.requests.GetSecurityListRequest;
import com.oracle.bmc.core.requests.GetVnicRequest;
import com.oracle.bmc.core.requests.ListVnicAttachmentsRequest;
import com.oracle.bmc.core.requests.UpdateSecurityListRequest;
import jakarta.inject.Singleton;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.session.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Singleton
public class GenericBenchmarkRunner {
    private static final Logger LOG = LoggerFactory.getLogger(GenericBenchmarkRunner.class);

    private static final String NETWORK = "10.0.0.0/16";
    private static final String PRIVATE_SUBNET = "10.0.0.0/18";
    private static final String RELAY_SUBNET = "10.0.254.0/24";
    static final String SERVER_IP = "10.0.0.2";

    private final ComputeClient computeClient;
    private final VirtualNetworkClient vcnClient;

    private final Compute compute;

    private final HyperfoilRunner.Factory hyperfoilRunnerFactory;

    private final SshFactory sshFactory;

    public GenericBenchmarkRunner(ComputeClient computeClient, VirtualNetworkClient vcnClient, Compute compute, HyperfoilRunner.Factory hyperfoilRunnerFactory, SshFactory sshFactory) throws Exception {
        this.computeClient = computeClient;
        this.vcnClient = vcnClient;
        this.compute = compute;
        this.hyperfoilRunnerFactory = hyperfoilRunnerFactory;
        this.sshFactory = sshFactory;
    }

    public void run(Path outputDirectory, OciLocation location, FrameworkRun run, LoadVariant loadVariant) throws Exception {
        try {
            Files.createDirectories(outputDirectory);
        } catch (FileAlreadyExistsException ignored) {
        }

        Throttle.VCN.take();
        Vcn vcn = vcnClient.createVcn(CreateVcnRequest.builder()
                .createVcnDetails(CreateVcnDetails.builder()
                        .compartmentId(location.compartmentId())
                        .displayName("Benchmark network")
                        .cidrBlock(NETWORK)
                        .build())
                .build()).getVcn();
        String vcnId = vcn.getId();
        Throttle.VCN.take();
        String natId = vcnClient.createNatGateway(CreateNatGatewayRequest.builder()
                .createNatGatewayDetails(CreateNatGatewayDetails.builder()
                        .compartmentId(location.compartmentId())
                        .vcnId(vcnId)
                        .displayName("NAT Gateway")
                        .build())
                .build()).getNatGateway().getId();
        Throttle.VCN.take();
        String internetId = vcnClient.createInternetGateway(CreateInternetGatewayRequest.builder()
                .createInternetGatewayDetails(CreateInternetGatewayDetails.builder()
                        .compartmentId(location.compartmentId())
                        .vcnId(vcnId)
                        .displayName("Internet Gateway")
                        .isEnabled(true)
                        .build())
                .build()).getInternetGateway().getId();
        Throttle.VCN.take();
        String privateRouteTable = vcnClient.createRouteTable(CreateRouteTableRequest.builder()
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
        String publicRouteTable = vcnClient.createRouteTable(CreateRouteTableRequest.builder()
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
        String privateSubnetId = vcnClient.createSubnet(CreateSubnetRequest.builder()
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
        String publicSubnetId = vcnClient.createSubnet(CreateSubnetRequest.builder()
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
        SecurityList securityList = vcnClient.getSecurityList(GetSecurityListRequest.builder()
                .securityListId(vcn.getDefaultSecurityListId())
                .build()).getSecurityList();
        List<IngressSecurityRule> ingressRules = new ArrayList<>(securityList.getIngressSecurityRules());
        // allow all internal traffic
        ingressRules.add(IngressSecurityRule.builder()
                .source("10.0.0.0/8")
                .sourceType(IngressSecurityRule.SourceType.CidrBlock)
                .protocol("all")
                .isStateless(true)
                .build());
        Throttle.VCN.take();
        vcnClient.updateSecurityList(UpdateSecurityListRequest.builder()
                .securityListId(vcn.getDefaultSecurityListId())
                .updateSecurityListDetails(UpdateSecurityListDetails.builder()
                        .ingressSecurityRules(ingressRules)
                        .build())
                .build());

        String benchmarkServer = compute.builder("benchmark-server", location, privateSubnetId)
                .privateIp(SERVER_IP)
                .launch();

        try (HyperfoilRunner hyperfoilRunner = hyperfoilRunnerFactory.create(outputDirectory)) {
            hyperfoilRunner.startAsync(location, privateSubnetId);

            String relayServerIp = createRelayServer(location, publicSubnetId);
            String relay = "opc@" + relayServerIp + ":22";
            hyperfoilRunner.setRelay(relay);

            compute.awaitStartup(benchmarkServer);
            try (ClientSession benchmarkServerClient = sshFactory.connect(benchmarkServer, SERVER_IP, relay);
                 OutputListener.Write log = new OutputListener.Write(Files.newOutputStream(outputDirectory.resolve("server.log")))) {

                LOG.info("Updating benchmark server");
                openFirewallPorts(benchmarkServerClient, log);
                run(benchmarkServerClient, "sudo yum update -y", log);

                run.setupAndRun(
                        benchmarkServerClient,
                        log,
                        () -> {
                            try (ClientSession relayClient = sshFactory.connect(null, relayServerIp, null)) {
                                switch (loadVariant.protocol()) {
                                    case HTTP1 -> run(relayClient, "curl --http1.1 http://" + SERVER_IP + ":8080/status", log);
                                    case HTTPS1 -> run(relayClient, "curl --http1.1 -k https://" + SERVER_IP + ":8443/status", log);
                                    case HTTPS2 -> run(relayClient, "curl --http2 -k https://" + SERVER_IP + ":8443/status", log);
                                }
                            }

                            hyperfoilRunner.benchmark(loadVariant.protocol(), loadVariant.body());
                        }
                );
            }
        }
    }

    static void openFirewallPorts(ClientSession benchmarkServerClient, OutputListener... log) throws IOException {
        try (ChannelExec session = benchmarkServerClient.createExecChannel("sudo tee /etc/nftables/main.nft");
             InputStream nft = GenericBenchmarkRunner.class.getResourceAsStream("/main.nft")) {
            session.setIn(nft);
            forwardOutput(session, log);
            session.open().await();
            joinAndCheck(session);
        }
        run(benchmarkServerClient, "sudo systemctl restart nftables", log);
    }

    public static void run(ClientSession client, String command, OutputListener... log) throws IOException {
        try (ChannelExec chan = client.createExecChannel(command)) {
            forwardOutput(chan, log);
            chan.open().await();
            joinAndCheck(chan);
        }
    }

    private static void joinAndCheck(ChannelExec cmd) throws IOException {
        joinAndCheck(cmd, 0);
    }

    private static void joinAndCheck(ChannelExec cmd, int expectedStatus) throws IOException {
        cmd.waitFor(ClientSession.REMOTE_COMMAND_WAIT_EVENTS, 0);
        if (cmd.getExitSignal() != null) {
            throw new IOException(cmd.getExitSignal());
        }
        if (cmd.getExitStatus() == null || cmd.getExitStatus() != expectedStatus) {
            throw new IOException("Exit status: " + cmd.getExitStatus());
        }
    }

    public static void forwardOutput(ChannelExec command, OutputListener... listeners) throws IOException {
        OutputListener.Stream stream = new OutputListener.Stream(List.of(listeners));
        command.setOut(stream);
        command.setErr(stream);
    }

    private String createRelayServer(OciLocation location, String subnetId) throws InterruptedException {
        LOG.info("Creating relay server");
        String relayServerInstance = compute.builder("relay-server", location, subnetId)
                .publicIp(true)
                .launch();

        compute.awaitStartup(relayServerInstance);

        Throttle.COMPUTE.take();
        String vnic = computeClient.listVnicAttachments(ListVnicAttachmentsRequest.builder()
                .compartmentId(location.compartmentId())
                .availabilityDomain(location.availabilityDomain())
                .instanceId(relayServerInstance)
                .build()).getItems().get(0).getVnicId();
        return vcnClient.getVnic(GetVnicRequest.builder()
                .vnicId(vnic)
                .build()).getVnic().getPublicIp();
    }
}
