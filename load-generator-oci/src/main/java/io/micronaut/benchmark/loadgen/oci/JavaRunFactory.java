package io.micronaut.benchmark.loadgen.oci;

import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.scp.client.ScpClientCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

@Singleton
public class JavaRunFactory {
    private static final Logger LOG = LoggerFactory.getLogger(JavaRunFactory.class);
    private static final String SHADOW_JAR_LOCATION = "shadow.jar";

    private final HotspotConfiguration hotspotConfiguration;
    private final NativeImageConfiguration nativeImageConfiguration;

    public JavaRunFactory(HotspotConfiguration hotspotConfiguration, NativeImageConfiguration nativeImageConfiguration) {
        this.hotspotConfiguration = hotspotConfiguration;
        this.nativeImageConfiguration = nativeImageConfiguration;
    }

    private static String optionsToString(String opts) {
        return opts.replaceAll("[:+=-]", "")
                .replace(" nofallback", "")
                .replaceAll(" +", "-")
                .toLowerCase(Locale.ROOT);
    }

    public RunBuilder createJavaRuns(String typePrefix) {
        return new RunBuilder(typePrefix);
    }

    public class RunBuilder {
        private final String typePrefix;
        private Path shadowJar;
        @Nullable
        private String configString;
        @Nullable
        private Object compileConfiguration;
        private byte[] boundLine;
        private String additionalNativeImageOptions;

        private RunBuilder(String typePrefix) {
            this.typePrefix = typePrefix;
            this.additionalNativeImageOptions = nativeImageConfiguration.getPrefixOptions().getOrDefault(typePrefix, "");
        }

        /**
         * Location of the jar to run.
         */
        public RunBuilder shadowJar(Path shadowJar) {
            if (!Files.exists(shadowJar)) {
                throw new IllegalArgumentException("File does not exist");
            }
            this.shadowJar = shadowJar;
            return this;
        }

        /**
         * The string representing the compile configuration. Used for the directory name of the output.
         */
        public RunBuilder configString(String configString) {
            this.configString = configString;
            return this;
        }

        /**
         * The compile configuration object for the benchmark index. This is serialized to JSON for the index.
         */
        public RunBuilder compileConfiguration(Object compileConfiguration) {
            this.compileConfiguration = compileConfiguration;
            return this;
        }

        /**
         * Log message when the server is bound and ready for requests. We wait for this log message before starting the
         * benchmark.
         */
        public RunBuilder boundOn(String message) {
            this.boundLine = message.getBytes(StandardCharsets.UTF_8);
            return this;
        }

        public Stream<FrameworkRun> build() {
            return Stream.concat(
                    hotspotConfiguration.getOptionChoices().stream().map(hotspotOptions -> new FrameworkRun() {
                        @Override
                        public String type() {
                            return typePrefix + "-hotspot";
                        }

                        @Override
                        public String name() {
                            return typePrefix + "-hotspot-" + configString + "-" + optionsToString(hotspotOptions);
                        }

                        @Override
                        public Object parameters() {
                            return compileConfiguration;
                        }

                        @Override
                        public void setupAndRun(ClientSession benchmarkServerClient, OutputListener.Write log, BenchmarkClosure benchmarkClosure, PhaseTracker.PhaseUpdater progress) throws Exception {
                            progress.update(BenchmarkPhase.INSTALLING_SOFTWARE);
                            SshUtil.run(benchmarkServerClient, "sudo yum install jdk-17-headless -y", log);
                            progress.update(BenchmarkPhase.DEPLOYING_SERVER);
                            ScpClientCreator.instance().createScpClient(benchmarkServerClient)
                                    .upload(shadowJar, SHADOW_JAR_LOCATION);
                            LOG.info("Starting benchmark server (hotspot, " + typePrefix + ")");
                            try (ChannelExec cmd = benchmarkServerClient.createExecChannel("java " + hotspotOptions + " -jar " + SHADOW_JAR_LOCATION)) {
                                OutputListener.Waiter waiter = new OutputListener.Waiter(ByteBuffer.wrap(boundLine));
                                SshUtil.forwardOutput(cmd, log, waiter);
                                cmd.open().verify();
                                waiter.awaitWithNextPattern(null);

                                benchmarkClosure.benchmark(progress);
                            }
                        }
                    }),
                    nativeImageConfiguration.getOptionChoices().stream().map(nativeImageOptions -> new FrameworkRun() {
                        @Override
                        public String type() {
                            return typePrefix + "-native";
                        }

                        @Override
                        public String name() {
                            return typePrefix + "-native-" + configString + "-" + optionsToString(nativeImageOptions);
                        }

                        @Override
                        public Object parameters() {
                            return compileConfiguration;
                        }

                        @Override
                        public void setupAndRun(ClientSession benchmarkServerClient, OutputListener.Write log, BenchmarkClosure benchmarkClosure, PhaseTracker.PhaseUpdater progress) throws Exception {

                            progress.update(BenchmarkPhase.INSTALLING_SOFTWARE);
                            SshUtil.run(benchmarkServerClient, "sudo yum install graalvm22-ee-17-jdk -y", log);
                            SshUtil.run(benchmarkServerClient, "sudo yum update oraclelinux-release-el9 -y", log);
                            SshUtil.run(benchmarkServerClient, "sudo yum config-manager --set-enabled ol9_codeready_builder", log);
                            SshUtil.run(benchmarkServerClient, "sudo yum install graalvm22-ee-17-native-image -y", log);
                            progress.update(BenchmarkPhase.DEPLOYING_SERVER);
                            ScpClientCreator.instance().createScpClient(benchmarkServerClient)
                                    .upload(shadowJar, SHADOW_JAR_LOCATION);
                            progress.update(BenchmarkPhase.BUILDING_PGO_IMAGE);
                            String niCommandBase = "native-image --no-fallback " + nativeImageOptions + " " + additionalNativeImageOptions;
                            SshUtil.run(benchmarkServerClient, niCommandBase + " --pgo-instrument -jar " + SHADOW_JAR_LOCATION + " pgo-instrument", log);
                            LOG.info("Starting benchmark server for PGO (native, micronaut)");
                            try (ChannelExec cmd = benchmarkServerClient.createExecChannel("./pgo-instrument")) {
                                OutputListener.Waiter waiter = new OutputListener.Waiter(ByteBuffer.wrap(boundLine));
                                SshUtil.forwardOutput(cmd, log, waiter);
                                cmd.open().verify();
                                waiter.awaitWithNextPattern(null);

                                benchmarkClosure.pgoLoad(progress);

                                SshUtil.interrupt(cmd);
                                SshUtil.joinAndCheck(cmd, 130);
                            }
                            progress.update(BenchmarkPhase.BUILDING_IMAGE);
                            SshUtil.run(benchmarkServerClient, niCommandBase + " --pgo -jar " + SHADOW_JAR_LOCATION + " optimized", log);
                            LOG.info("Starting benchmark server (native, " + typePrefix + ")");
                            try (ChannelExec cmd = benchmarkServerClient.createExecChannel("./optimized")) {
                                OutputListener.Waiter waiter = new OutputListener.Waiter(ByteBuffer.wrap(boundLine));
                                SshUtil.forwardOutput(cmd, log, waiter);
                                cmd.open().verify();
                                waiter.awaitWithNextPattern(null);

                                benchmarkClosure.benchmark(progress);
                            }
                        }
                    })
            );
        }
    }
}
