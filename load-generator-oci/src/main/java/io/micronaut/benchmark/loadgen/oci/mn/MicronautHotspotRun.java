package io.micronaut.benchmark.loadgen.oci.mn;

import io.micronaut.benchmark.loadgen.oci.BenchmarkPhase;
import io.micronaut.benchmark.loadgen.oci.FrameworkRun;
import io.micronaut.benchmark.loadgen.oci.GenericBenchmarkRunner;
import io.micronaut.benchmark.loadgen.oci.OutputListener;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.scp.client.ScpClientCreator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

public class MicronautHotspotRun implements FrameworkRun {
    private static final Logger LOG = LoggerFactory.getLogger(MicronautHotspotRun.class);

    private static final String SHADOW_JAR_LOCATION = "shadow.jar";

    private final Map<String, String> compileConfiguration;

    private final Path shadowFile;

    public MicronautHotspotRun(Map<String, String> compileConfiguration) {
        this.compileConfiguration = compileConfiguration;
        this.shadowFile = Path.of("build/libs", variantName() + "-all.jar");

        if (!Files.isRegularFile(shadowFile)) {
            throw new IllegalStateException("Shadow JAR file does not exist for this configuration: " + shadowFile);
        }
    }

    private String variantName() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> e : compileConfiguration.entrySet()) {
            builder.append(e.getKey()).append('-').append(e.getValue()).append('-');
        }
        builder.setLength(builder.length() - 1); // remove trailing dash
        return builder.toString();
    }

    @Override
    public String type() {
        return "mn-hotspot";
    }

    @Override
    public String name() {
        return "mn-hotspot-" + variantName();
    }

    @Override
    public Object parameters() {
        return compileConfiguration;
    }

    @Override
    public void setupAndRun(
            ClientSession benchmarkServerClient,
            OutputListener.Write log,
            BenchmarkClosure benchmarkClosure,
            Consumer<BenchmarkPhase> progress) throws Exception {
        progress.accept(BenchmarkPhase.INSTALLING_SOFTWARE);
        GenericBenchmarkRunner.run(benchmarkServerClient, "sudo yum install jdk-17-headless -y", log);
        progress.accept(BenchmarkPhase.DEPLOYING_SERVER);
        ScpClientCreator.instance().createScpClient(benchmarkServerClient)
                .upload(shadowFile, SHADOW_JAR_LOCATION);
        LOG.info("Starting benchmark server (hotspot, micronaut)");
        try (ChannelExec cmd = benchmarkServerClient.createExecChannel("java -XX:+UseG1GC -Xmx1G -jar " + SHADOW_JAR_LOCATION)) {
            OutputListener.Waiter waiter = new OutputListener.Waiter(ByteBuffer.wrap("io.micronaut.runtime.Micronaut - Startup completed".getBytes(StandardCharsets.UTF_8)));
            GenericBenchmarkRunner.forwardOutput(cmd, log, waiter);
            cmd.open().verify();
            waiter.awaitWithNextPattern(null);

            progress.accept(BenchmarkPhase.BENCHMARKING);
            benchmarkClosure.benchmark();
        }
    }
}
