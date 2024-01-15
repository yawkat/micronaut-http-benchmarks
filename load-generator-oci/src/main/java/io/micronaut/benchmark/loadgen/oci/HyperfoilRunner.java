package io.micronaut.benchmark.loadgen.oci;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hyperfoil.api.config.Benchmark;
import io.hyperfoil.api.config.BenchmarkBuilder;
import io.hyperfoil.api.config.ScenarioBuilder;
import io.hyperfoil.api.statistics.StatisticsSummary;
import io.hyperfoil.client.RestClient;
import io.hyperfoil.client.RestClientException;
import io.hyperfoil.controller.Client;
import io.hyperfoil.controller.model.RequestStatisticsResponse;
import io.hyperfoil.controller.model.RequestStats;
import io.hyperfoil.core.util.ConstantBytesGenerator;
import io.hyperfoil.http.api.HttpMethod;
import io.hyperfoil.http.config.HttpPluginBuilder;
import io.hyperfoil.http.statistics.HttpStats;
import io.hyperfoil.http.steps.HttpStepCatalog;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.scheduling.TaskExecutors;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClosedException;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.scp.client.ScpClient;
import org.apache.sshd.scp.client.ScpClientCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private static final int SESSION_LIMIT_FACTOR = 10;

    private final Factory factory;
    private final CompletableFuture<SshFactory.Relay> relay = new CompletableFuture<>();
    private final CompletableFuture<Client> client = new CompletableFuture<>();
    private final CompletableFuture<Void> terminate = new CompletableFuture<>();
    private final Path logDirectory;
    private final HyperfoilInstances instances;
    private final Future<?> worker;
    private final List<Compute.Instance> computeInstances = new CopyOnWriteArrayList<>();
    private ClientSession controllerSession;
    private ResilientSshPortForwarder controllerPortForward;

    static {
        System.setProperty("io.hyperfoil.cli.request.timeout", "30000");
    }

    private HyperfoilRunner(Factory factory, Path logDirectory, OciLocation location, String privateSubnetId) throws Exception {
        this.factory = factory;
        this.logDirectory = logDirectory;

        try {
            Files.createDirectories(logDirectory);
        } catch (FileAlreadyExistsException ignored) {}

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

        SshFactory.Relay relay = this.relay.get();

        try (
                OutputListener.Write log = new OutputListener.Write(Files.newOutputStream(logDirectory.resolve("hyperfoil.log")));
                ClientSession controllerSession = factory.sshFactory.connect(instances.controller, HYPERFOIL_CONTROLLER_IP, relay)) {
            this.controllerSession = controllerSession;

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

            try (ChannelExec controllerCommand = controllerSession.createExecChannel(REMOTE_HYPERFOIL_LOCATION + "/bin/controller.sh -Djgroups.join_timeout=20000");
                 ResilientSshPortForwarder controllerPortForward = factory.resilientForwarderFactory.create(
                         () -> factory.sshFactory.connect(instances.controller, HYPERFOIL_CONTROLLER_IP, relay),
                         new SshdSocketAddress("localhost", 8090)
                 );
                 RestClient client = new RestClient(
                         factory.vertx,
                         controllerPortForward.address().getHostName(),
                         controllerPortForward.address().getPort(),
                         false, true, null)) {
                this.controllerPortForward = controllerPortForward;

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
                    LOG.info("Connecting to hyperfoil controller forwarded at {}", controllerPortForward.address());
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
                        for (String agent : Infrastructure.retry(client::agents, controllerPortForward::disconnect)) {
                            client.downloadLog(agent, null, 0, logDirectory.resolve(agent.replaceAll("[^0-9a-zA-Z]", "") + ".log").toFile());
                        }
                    } catch (Exception e) {
                        LOG.error("Failed to download agent logs", e);
                    }
                }
            } finally {
                this.controllerSession = null;
                LOG.info("Closing benchmark client");
            }
        } catch (Throwable t) {
            this.client.completeExceptionally(t);
            this.relay.completeExceptionally(t);
            throw t;
        }
    }

    public void setRelay(SshFactory.Relay relay) {
        this.relay.complete(relay);
    }

    public FrameworkRun.BenchmarkClosure benchmarkClosure(Path outputDirectory, Protocol protocol, byte[] body) {
        return new FrameworkRun.BenchmarkClosure() {
            @Override
            public void benchmark(PhaseTracker.PhaseUpdater progress) throws Exception {
                HyperfoilRunner.this.benchmark(outputDirectory, protocol, body, progress, false);
            }

            @Override
            public void pgoLoad(PhaseTracker.PhaseUpdater progress) throws Exception {
                HyperfoilRunner.this.benchmark(outputDirectory, protocol, body, progress, true);
            }
        };
    }

    private void benchmark(Path outputDirectory, Protocol protocol, byte[] body, PhaseTracker.PhaseUpdater progress, boolean forPgo) throws Exception {
        BenchmarkPhase benchmarkPhase = forPgo ? BenchmarkPhase.PGO : BenchmarkPhase.BENCHMARKING;

        progress.update(benchmarkPhase);
        String ip = Infrastructure.SERVER_IP;
        int port = protocol == Protocol.HTTP1 ? 8080 : 8443;
        io.hyperfoil.http.config.Protocol prot = protocol == Protocol.HTTP1 ? io.hyperfoil.http.config.Protocol.HTTP : io.hyperfoil.http.config.Protocol.HTTPS;

        String statusUri = prot.scheme + "://" + ip + ":" + port + "/status";
        String findUri = prot.scheme + "://" + ip + ":" + port + "/search/find";
        String curlBase = "curl " + (protocol == Protocol.HTTPS2 ? "--http2" : "--http1.1") + " -H 'Accept: application/json' --silent --insecure ";
        Infrastructure.retry(() -> {
            try (OutputListener.Write write = new OutputListener.Write(Files.newOutputStream(outputDirectory.resolve("status.json")))) {
                SshUtil.run(controllerSession, curlBase + statusUri, write);
            }
            return null;
        }, controllerPortForward::disconnect);
        Infrastructure.retry(() -> {
            String testBody = factory.objectMapper.writeValueAsString(new Input(List.of("foo", "bar"), "ar"));
            ByteArrayOutputStream resp = new ByteArrayOutputStream();
            try (OutputListener.Write write = new OutputListener.Write(resp)) {
                SshUtil.run(controllerSession, curlBase + "-d '" + testBody + "' -H 'Content-Type: application/json' " + findUri, write);
            }
            Result result = factory.objectMapper.readValue(resp.toByteArray(), Result.class);
            if (result.listIndex != 1 || result.stringIndex != 1) {
                throw new InvalidatesBenchmarkException("Response to test request was incorrect: " + resp.toString(StandardCharsets.UTF_8));
            }
            return null;
        });

        String name = "benchmark-" + UUID.randomUUID();
        BenchmarkBuilder benchmark = BenchmarkBuilder.builder()
                .name(name)
                .failurePolicy(Benchmark.FailurePolicy.CANCEL);
        for (int i = 0; i < factory.config.agentCount; i++) {
            benchmark.addAgent("agent" + i, agentIp(i) + ":22", Map.of());
        }

        benchmark.addPlugin(HttpPluginBuilder::new)
                .http()
                .protocol(prot)
                .host(ip)
                .port(port)
                .allowHttp1x(protocol != Protocol.HTTPS2)
                .allowHttp2(protocol == Protocol.HTTPS2)
                .sharedConnections(factory.config.sharedConnections)
                .pipeliningLimit(factory.config.pipeliningLimit);

        List<String> phaseNames = new ArrayList<>();
        if (!forPgo) {
            phaseNames.add("warmup");
            prepareScenario(body, ip, port, benchmark.addPhase("warmup")
                    .constantRate(factory.config.compileOps)
                    .maxSessions(factory.config.compileOps * SESSION_LIMIT_FACTOR)
                    .duration(TimeUnit.MILLISECONDS.convert(factory.config.warmupDuration))
                    .isWarmup(true)
                    .scenario());
            String lastPhase = "warmup";
            for (int i = 0; i < factory.config.ops.size(); i++) {
                int ops = factory.config.ops.get(i);
                String phaseName = "main/" + i;
                phaseNames.add(phaseName);
                prepareScenario(body, ip, port, benchmark.addPhase(phaseName)
                        .constantRate(0)
                        .usersPerSec(ops)
                        .maxSessions(ops * SESSION_LIMIT_FACTOR)
                        .duration(TimeUnit.MILLISECONDS.convert(factory.config.benchmarkDuration))
                        .isWarmup(false)
                        .startAfter(lastPhase)
                        .scenario());
                lastPhase = phaseName;
            }
        } else {
            phaseNames.add("pgo");
            prepareScenario(body, ip, port, benchmark.addPhase("pgo")
                    .constantRate(factory.config.compileOps)
                    .maxSessions(factory.config.compileOps * SESSION_LIMIT_FACTOR)
                    .duration(TimeUnit.MILLISECONDS.convert(factory.config.pgoDuration))
                    .isWarmup(false)
                    .scenario());
        }

        Benchmark builtBenchmark = benchmark.build();

        Client client = this.client.get();
        Client.BenchmarkRef benchmarkRef = client.register(builtBenchmark, null);
        Client.RunRef runRef = benchmarkRef.start("run", Map.of());
        long startTime = System.nanoTime();
        String lastPhase = null;
        while (true) {
            RequestStatisticsResponse recentStats = Infrastructure.retry(runRef::statsRecent, controllerPortForward::disconnect);
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
                if (!Objects.equals(statistic.phase, lastPhase)) {
                    lastPhase = statistic.phase;
                    double progressPercent = (phaseNames.indexOf(statistic.phase) + 1.0) / (phaseNames.size() + 1);
                    progress.update(benchmarkPhase, progressPercent);
                }
            }
            LOG.info("{}", log);
            TimeUnit.SECONDS.sleep(5);
        }

        record StatsAllWrapper(byte[] resultBytes, StatsAll statsAll) {}

        StatsAllWrapper wrapper = Infrastructure.retry(() -> {
            byte[] bytes = runRef.statsAll("json");
            return new StatsAllWrapper(bytes, factory.objectMapper.readValue(bytes, StatsAll.class));
        }, controllerPortForward::disconnect);
        List<String> benchmarkFailures = new ArrayList<>();
        boolean invalidatesBenchmark = false;
        for (StatsAll.Info.Error error : wrapper.statsAll.info.errors) {
            if (error.msg.contains("Jitter watchdog was not invoked")) {
                LOG.warn("Jitter in watchdog agent. Log message: {}", error.msg);
                continue;
            }
            benchmarkFailures.add(error.agent + ": " + error.msg);
        }
        for (StatsAll.SlaFailure failure : wrapper.statsAll.failures) {
            if (failure.phase.equals("pgo") || failure.phase.equals("warmup")) {
                benchmarkFailures.add("SLA failure in " + failure.phase + " phase: " + failure.message);
                invalidatesBenchmark = true;
            }
        }
        for (StatsAll.Stats stats : wrapper.statsAll.stats) {
            if (stats.total.summary.responseCount == 0) {
                benchmarkFailures.add("No responses in phase " + stats.phase);
                invalidatesBenchmark = true;
            }
        }

        if (!forPgo || !benchmarkFailures.isEmpty()) {
            LOG.info("Benchmark complete, writing output");
            Path outputPath = outputDirectory.resolve(benchmarkFailures.isEmpty() ? "output.json" : "output-failed.json");
            Files.write(outputPath, wrapper.resultBytes);
            if (!benchmarkFailures.isEmpty()) {
                String msg = String.join("\n", benchmarkFailures) + "\nOutput written at: " + outputPath;
                throw invalidatesBenchmark ? new InvalidatesBenchmarkException(msg) : new Exception(msg);
            }
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
        private final ObjectMapper objectMapper;
        private final ResilientSshPortForwarder.Factory resilientForwarderFactory;
        private final Vertx vertx;

        Factory(Compute compute, SshFactory sshFactory, @Named(TaskExecutors.IO) ExecutorService executor, HyperfoilConfiguration config, ObjectMapper objectMapper, ResilientSshPortForwarder.Factory resilientForwarderFactory) {
            this.compute = compute;
            this.sshFactory = sshFactory;
            this.executor = executor;
            this.config = config;
            this.objectMapper = objectMapper;
            this.resilientForwarderFactory = resilientForwarderFactory;
            this.vertx = Vertx.vertx();

            objectMapper.registerSubtypes(HttpStats.class);
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
        private List<Integer> ops;
        private int sharedConnections;
        private int pipeliningLimit;

        public int getCompileOps() {
            return compileOps;
        }

        public void setCompileOps(int compileOps) {
            this.compileOps = compileOps;
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

        public List<Integer> getOps() {
            return ops;
        }

        public void setOps(List<Integer> ops) {
            this.ops = ops;
        }

        public int getSharedConnections() {
            return sharedConnections;
        }

        public void setSharedConnections(int sharedConnections) {
            this.sharedConnections = sharedConnections;
        }

        public int getPipeliningLimit() {
            return pipeliningLimit;
        }

        public void setPipeliningLimit(int pipeliningLimit) {
            this.pipeliningLimit = pipeliningLimit;
        }
    }

    private record HyperfoilInstances(
            Compute.Instance controller,
            List<Compute.Instance> agents
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StatsAll(
            Info info,
            List<SlaFailure> failures,
            List<Stats> stats
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Info(
                List<Error> errors
        ) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            private record Error(
                    String agent,
                    String msg
            ) {

            }
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record SlaFailure(
                String phase,
                String message
        ) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        private record Stats(
                String phase,
                Total total
        ) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            private record Total(StatisticsSummary summary) {
            }
        }
    }

    private record Input(List<String> haystack, String needle) {}

    private record Result(int listIndex, int stringIndex) {}
}
