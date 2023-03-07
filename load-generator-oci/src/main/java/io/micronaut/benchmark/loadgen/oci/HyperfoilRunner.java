package io.micronaut.benchmark.loadgen.oci;

import io.hyperfoil.api.config.Benchmark;
import io.hyperfoil.api.config.BenchmarkBuilder;
import io.hyperfoil.api.config.ScenarioBuilder;
import io.hyperfoil.client.RestClient;
import io.hyperfoil.client.RestClientException;
import io.hyperfoil.controller.Client;
import io.hyperfoil.controller.model.RequestStatisticsResponse;
import io.hyperfoil.controller.model.RequestStats;
import io.hyperfoil.core.util.ConstantBytesGenerator;
import io.hyperfoil.http.api.HttpMethod;
import io.hyperfoil.http.config.HttpPluginBuilder;
import io.hyperfoil.http.steps.HttpStepCatalog;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.scheduling.TaskExecutors;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClosedException;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.forward.PortForwardingTracker;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.scp.client.ScpClient;
import org.apache.sshd.scp.client.ScpClientCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class HyperfoilRunner implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(HyperfoilRunner.class);

    private static final String HYPERFOIL_CONTROLLER_IP = "10.0.0.3";
    private static final String HYPERFOIL_AGENT_PREFIX = "10.0.1.";
    private static final Path LOCAL_HYPERFOIL_LOCATION = Path.of("/home/yawkat/bin/hyperfoil-0.24.1");
    private static final String REMOTE_HYPERFOIL_LOCATION = "hyperfoil";

    private final Factory factory;
    private final CompletableFuture<String> relay = new CompletableFuture<>();
    private final CompletableFuture<Client> client = new CompletableFuture<>();
    private final CompletableFuture<Void> terminate = new CompletableFuture<>();
    private final Path outputDirectory;
    private final HyperfoilInstances instances;
    private final Future<?> worker;
    private final List<Compute.Instance> computeInstances = new CopyOnWriteArrayList<>();

    static {
        System.setProperty("io.hyperfoil.cli.request.timeout", "120000");
    }

    private HyperfoilRunner(Factory factory, Path outputDirectory, OciLocation location, String privateSubnetId) throws Exception {
        this.factory = factory;
        this.outputDirectory = outputDirectory;

        // fail fast if we can't create the instances
        instances = createInstances(location, privateSubnetId);
        worker = factory.executor.submit(MdcTracker.copyMdc(() -> {
            try (AutoCloseable ignored = this::terminateAndWait) {
                deploy();
            } catch (InterruptedException | InterruptedIOException ignored) {
            } catch (Exception e) {
                LOG.error("Failed to deploy hyperfoil server", e);
            }
            terminate.complete(null);
            return null;
        }));
    }

    private HyperfoilInstances createInstances(OciLocation location, String privateSubnetId) throws Exception {
        Compute.Instance hyperfoilController = factory.compute.builder("hyperfoil-controller", location, privateSubnetId)
                .privateIp(HYPERFOIL_CONTROLLER_IP)
                .launch();
        try {
            computeInstances.add(hyperfoilController);
            List<Compute.Instance> agents = new ArrayList<>();
            for (int i = 0; i < factory.config.agentCount; i++) {
                Compute.Instance instance = factory.compute.builder("hyperfoil-agent", location, privateSubnetId)
                        .privateIp(agentIp(i))
                        .launch();
                agents.add(instance);
                computeInstances.add(instance);
            }
            return new HyperfoilInstances(hyperfoilController, agents);
        } catch (Exception e) {
            try {
                terminateAndWait();
            } catch (Exception f) {
                e.addSuppressed(f);
            }
            throw e;
        }
    }

    private void terminateAndWait() throws Exception {
        for (Compute.Instance computeInstance : computeInstances) {
            computeInstance.terminateAsync();
        }
        for (Compute.Instance computeInstance : computeInstances) {
            computeInstance.close();
        }
    }

    private void deploy() throws Exception {
        instances.controller.awaitStartup();

        String relay = this.relay.get();

        try (
                OutputListener.Write log = new OutputListener.Write(Files.newOutputStream(outputDirectory.resolve("hyperfoil.log")));
                ClientSession controllerSession = factory.sshFactory.connect(instances.controller, HYPERFOIL_CONTROLLER_IP, relay)) {

            List<Callable<Void>> setupTasks = new ArrayList<>();

            setupTasks.add(() -> {
                SshUtil.run(controllerSession, "sudo yum install jdk-17-headless -y", log);
                ScpClientCreator.instance().createScpClient(controllerSession).upload(LOCAL_HYPERFOIL_LOCATION, REMOTE_HYPERFOIL_LOCATION, ScpClient.Option.Recursive, ScpClient.Option.PreserveAttributes);
                factory.sshFactory.deployPrivateKey(controllerSession);

                SshUtil.openFirewallPorts(controllerSession);
                return null;
            });

            for (int i = 0; i < instances.agents.size(); i++) {
                Compute.Instance agent = instances.agents.get(i);

                String agentIp = agentIp(i);
                setupTasks.add(() -> {
                    agent.awaitStartup();
                    try (ClientSession agentSession = factory.sshFactory.connect(agent, agentIp, relay)) {
                        SshUtil.openFirewallPorts(agentSession);
                        SshUtil.run(agentSession, "sudo yum install jdk-17-headless -y", log);
                    }
                    return null;
                });
            }

            for (Future<?> f : factory.executor.invokeAll(setupTasks)) {
                f.get();
            }

            try (ChannelExec controllerCommand = controllerSession.createExecChannel(REMOTE_HYPERFOIL_LOCATION + "/bin/controller.sh");
                 PortForwardingTracker controllerPortForward = controllerSession.createLocalPortForwardingTracker(new SshdSocketAddress("localhost", 0), new SshdSocketAddress("localhost", 8090));
                 RestClient client = new RestClient(
                         Vertx.vertx(),
                         controllerPortForward.getBoundAddress().getHostName(),
                         controllerPortForward.getBoundAddress().getPort(),
                         false, true, null)) {
                SshUtil.forwardOutput(controllerCommand, log);
                controllerCommand.open().verify();

                while (true) {
                    try {
                        client.ping();
                        break;
                    } catch (RestClientException e) {
                        if (!(e.getCause() instanceof HttpClosedException hce) || !hce.getMessage().equals("Connection was closed")) {
                            throw e;
                        }
                    }
                    if (!controllerCommand.isOpen()) {
                        throw new IllegalStateException("Controller exec channel closed, did the controller die?");
                    }
                    LOG.info("Connecting to hyperfoil controller forwarded at {}", controllerPortForward.getBoundAddress());
                    TimeUnit.SECONDS.sleep(1);
                }

                this.client.complete(client);

                try {
                    //noinspection InfiniteLoopStatement
                    while (true) {
                        synchronized (this) {
                            wait(); // wait for interrupt
                        }
                    }
                } finally {
                    LOG.info("Downloading agent logs…");
                    try {
                        for (String agent : GenericBenchmarkRunner.retry(client::agents)) {
                            client.downloadLog(agent, null, 0, outputDirectory.resolve(agent.replaceAll("[^0-9a-zA-Z]", "") + ".log").toFile());
                        }
                    } catch (Exception e) {
                        LOG.error("Failed to download agent logs", e);
                    }
                }
            } finally {
                LOG.info("Closing benchmark client");
            }
        } catch (Throwable t) {
            this.client.completeExceptionally(t);
            this.relay.completeExceptionally(t);
            throw t;
        }
    }

    public void setRelay(String relay) {
        this.relay.complete(relay);
    }

    public FrameworkRun.BenchmarkClosure benchmarkClosure(Protocol protocol, byte[] body) {
        return new FrameworkRun.BenchmarkClosure() {
            @Override
            public void benchmark() throws Exception {
                // todo: check status endpoint
                HyperfoilRunner.this.benchmark(protocol, body, false);
            }

            @Override
            public void pgoLoad() throws Exception {
                HyperfoilRunner.this.benchmark(protocol, body, true);
            }
        };
    }

    private void benchmark(Protocol protocol, byte[] body, boolean forPgo) throws Exception {
        String name = "benchmark-" + UUID.randomUUID();
        BenchmarkBuilder benchmark = BenchmarkBuilder.builder()
                .name(name)
                .failurePolicy(Benchmark.FailurePolicy.CANCEL);
        for (int i = 0; i < factory.config.agentCount; i++) {
            benchmark.addAgent("agent" + i, agentIp(i) + ":22", Map.of());
        }

        String ip = GenericBenchmarkRunner.SERVER_IP;
        int port = protocol == Protocol.HTTP1 ? 8080 : 8443;

        benchmark.addPlugin(HttpPluginBuilder::new)
                .http()
                .protocol(protocol == Protocol.HTTP1 ? io.hyperfoil.http.config.Protocol.HTTP : io.hyperfoil.http.config.Protocol.HTTPS)
                .host(ip)
                .port(port)
                .allowHttp1x(protocol != Protocol.HTTPS2)
                .allowHttp2(protocol == Protocol.HTTPS2);

        if (!forPgo) {
            prepareScenario(body, ip, port, benchmark.addPhase("warmup")
                    .constantRate(factory.config.compileOps)
                    .duration(TimeUnit.MILLISECONDS.convert(factory.config.warmupDuration))
                    .isWarmup(true)
                    .scenario());
            prepareScenario(body, ip, port, benchmark.addPhase("main")
                    .constantRate(0)
                    .usersPerSec(factory.config.initialOps, factory.config.incrementOps)
                    .maxIterations(factory.config.maxIterations)
                    .duration(TimeUnit.MILLISECONDS.convert(factory.config.benchmarkDuration))
                    .isWarmup(false)
                    .startAfter("warmup")
                    .scenario());
        } else {
            prepareScenario(body, ip, port, benchmark.addPhase("main")
                    .constantRate(factory.config.compileOps)
                    .duration(TimeUnit.MILLISECONDS.convert(factory.config.pgoDuration))
                    .isWarmup(false)
                    .scenario());
        }

        Client client = this.client.get();
        Client.BenchmarkRef benchmarkRef = client.register(benchmark.build(), null);
        Client.RunRef runRef = benchmarkRef.start("run", Map.of());
        long startTime = System.nanoTime();
        while (true) {
            RequestStatisticsResponse recentStats = GenericBenchmarkRunner.retry(runRef::statsRecent);
            if (recentStats.status.equals("TERMINATED")) {
                break;
            }
            if (recentStats.status.equals("INITIALIZING")) {
                if (System.nanoTime() - startTime > TimeUnit.MINUTES.toNanos(5)) {
                    throw new TimeoutException("Benchmark stuck too long in INITIALIZING state");
                }
            }
            StringBuilder log = new StringBuilder("Benchmark progress").append(forPgo ? " (PGO): " : ": ").append(recentStats.status);
            for (RequestStats statistic : recentStats.statistics) {
                log.append(' ').append(statistic.metric).append(':').append(statistic.phase).append(":mean=").append(statistic.summary.meanResponseTime);
            }
            LOG.info("{}", log);
            TimeUnit.SECONDS.sleep(5);
        }
        if (!forPgo) {
            LOG.info("Benchmark complete, writing output");
            Files.write(outputDirectory.resolve("output.json"), GenericBenchmarkRunner.retry(() -> runRef.statsAll("json")));
        }
    }

    private static void prepareScenario(byte[] body, String ip, int port, ScenarioBuilder warmup) {
        warmup.initialSequence("test")
                .step(HttpStepCatalog.class)
                .httpRequest(HttpMethod.POST)
                .authority(ip + ":" + port)
                .path("/search/find")
                // MUST be lowercase for HTTP/2
                .headers().header("content-type", "application/json").endHeaders()
                .body(new ConstantBytesGenerator(body));
    }

    public void terminateAsync() {
        worker.cancel(true);
    }

    @Override
    public void close() throws Exception {
        terminateAsync();
        terminate.get();
    }

    private static String agentIp(int i) {
        return HYPERFOIL_AGENT_PREFIX + (i + 1);
    }

    @Singleton
    static final class Factory {
        private final Compute compute;
        private final SshFactory sshFactory;
        private final ExecutorService executor;
        private final HyperfoilConfiguration config;

        Factory(Compute compute, SshFactory sshFactory, @Named(TaskExecutors.IO) ExecutorService executor, HyperfoilConfiguration config) {
            this.compute = compute;
            this.sshFactory = sshFactory;
            this.executor = executor;
            this.config = config;
        }

        public HyperfoilRunner launch(Path outputDirectory, OciLocation location, String privateSubnetId) throws Exception {
            return new HyperfoilRunner(this, outputDirectory, location, privateSubnetId);
        }
    }

    @ConfigurationProperties("hyperfoil")
    public static final class HyperfoilConfiguration {
        private int agentCount;
        private Duration warmupDuration;
        private Duration benchmarkDuration;
        private Duration pgoDuration;

        private int compileOps;
        private int initialOps;
        private int incrementOps;
        private int maxIterations;

        public int getCompileOps() {
            return compileOps;
        }

        public void setCompileOps(int compileOps) {
            this.compileOps = compileOps;
        }

        public int getInitialOps() {
            return initialOps;
        }

        public void setInitialOps(int initialOps) {
            this.initialOps = initialOps;
        }

        public int getIncrementOps() {
            return incrementOps;
        }

        public void setIncrementOps(int incrementOps) {
            this.incrementOps = incrementOps;
        }

        public int getMaxIterations() {
            return maxIterations;
        }

        public void setMaxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
        }

        public int getAgentCount() {
            return agentCount;
        }

        public void setAgentCount(int agentCount) {
            this.agentCount = agentCount;
        }

        public Duration getWarmupDuration() {
            return warmupDuration;
        }

        public void setWarmupDuration(Duration warmupDuration) {
            this.warmupDuration = warmupDuration;
        }

        public Duration getBenchmarkDuration() {
            return benchmarkDuration;
        }

        public void setBenchmarkDuration(Duration benchmarkDuration) {
            this.benchmarkDuration = benchmarkDuration;
        }

        public Duration getPgoDuration() {
            return pgoDuration;
        }

        public void setPgoDuration(Duration pgoDuration) {
            this.pgoDuration = pgoDuration;
        }
    }

    private record HyperfoilInstances(
            Compute.Instance controller,
            List<Compute.Instance> agents
    ) {}
}
