package io.micronaut.benchmark.loadgen.oci;

import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.model.CreateVnicDetails;
import com.oracle.bmc.core.model.Image;
import com.oracle.bmc.core.model.LaunchInstanceDetails;
import com.oracle.bmc.core.model.LaunchInstanceShapeConfigDetails;
import com.oracle.bmc.core.model.LaunchOptions;
import com.oracle.bmc.core.requests.GetInstanceRequest;
import com.oracle.bmc.core.requests.LaunchInstanceRequest;
import com.oracle.bmc.core.requests.ListImagesRequest;
import com.oracle.bmc.core.requests.TerminateInstanceRequest;
import com.oracle.bmc.model.BmcException;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.EachProperty;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Singleton
public class Compute {
    private static final Logger LOG = LoggerFactory.getLogger(Compute.class);

    private final ComputeConfiguration computeConfiguration;
    private final ComputeClient computeClient;
    private final SshFactory sshFactory;

    private final Map<String, List<Image>> imagesByCompartment = new ConcurrentHashMap<>();

    public Compute(ComputeConfiguration computeConfiguration, ComputeClient computeClient, SshFactory sshFactory) {
        this.computeConfiguration = computeConfiguration;
        this.computeClient = computeClient;
        this.sshFactory = sshFactory;
    }

    private List<Image> images(String compartment) {
        return imagesByCompartment.computeIfAbsent(compartment, k -> computeClient.listImages(ListImagesRequest.builder()
                .compartmentId(k)
                .build()).getItems());
    }

    public Launch builder(String instanceType, OciLocation location, String subnetId) {
        return new Launch(instanceType, computeConfiguration.instanceTypes.get(instanceType), location, subnetId);
    }

    public class Launch {
        private final String displayName;
        private final ComputeConfiguration.InstanceType instanceType;
        private final OciLocation location;
        private final String subnetId;
        private String privateIp = null;
        private boolean publicIp = false;
        private String publicKey = sshFactory.publicKey();

        private Launch(String displayName, ComputeConfiguration.InstanceType instanceType, OciLocation location, String subnetId) {
            this.displayName = displayName;
            this.instanceType = Objects.requireNonNull(instanceType, "instanceType");
            this.location = location;
            this.subnetId = subnetId;
        }

        public Launch privateIp(String privateIp) {
            this.privateIp = privateIp;
            return this;
        }

        public Launch publicIp(boolean publicIp) {
            this.publicIp = publicIp;
            return this;
        }

        public Launch publicKey(String publicKey) {
            this.publicKey = publicKey;
            return this;
        }

        public Instance launch() throws InterruptedException {
            CreateVnicDetails.Builder vnicDetails = CreateVnicDetails.builder()
                    .subnetId(subnetId)
                    .assignPublicIp(publicIp);
            if (privateIp != null) {
                vnicDetails.privateIp(privateIp);
            }
            Throttle.COMPUTE.takeUninterruptibly();
            List<Image> images = images(location.compartmentId());
            Image image = images.stream()
                    .filter(i -> i.getId().equals(instanceType.image) || i.getDisplayName().equals(instanceType.image))
                    .findAny()
                    .orElseThrow(() -> new NoSuchElementException("Image not found. Available images are: " + images.stream().map(Image::getDisplayName).toList()));
            while (true) {
                try {
                    String id = computeClient.launchInstance(LaunchInstanceRequest.builder()
                            .launchInstanceDetails(LaunchInstanceDetails.builder()
                                    .compartmentId(location.compartmentId())
                                    .availabilityDomain(location.availabilityDomain())
                                    .displayName(displayName)
                                    .shape(instanceType.shape)
                                    .shapeConfig(LaunchInstanceShapeConfigDetails.builder()
                                            .ocpus(instanceType.ocpus)
                                            .memoryInGBs(instanceType.memoryInGb)
                                            .build())
                                    .createVnicDetails(vnicDetails.build())
                                    .imageId(image.getId())
                                    .metadata(Map.of(
                                            "ssh_authorized_keys",
                                            publicKey
                                    ))
                                    .launchOptions(LaunchOptions.builder()
                                            .networkType(LaunchOptions.NetworkType.Vfio)
                                            .build())
                                    .build())
                            .build()).getInstance().getId();
                    return new Instance(id);
                } catch (BmcException bmce) {
                    if (bmce.getStatusCode() == 429) {
                        LOG.info("429 while launching instance! Waiting 30s.");
                        TimeUnit.SECONDS.sleep(30);
                        continue;
                    }
                    throw bmce;
                }
            }
        }
    }

    public class Instance implements AutoCloseable {
        final String id;

        private boolean terminating = false;

        Instance(String id) {
            this.id = id;
        }

        /**
         * Check that the given instance is provisioning or started.
         *
         * @return {@code true} if the instance is running, {@code false} if it is still being set up
         * @throws IllegalStateException if the instance is shutting down
         */
        public boolean checkStarted() {
            Throttle.COMPUTE.takeUninterruptibly();
            com.oracle.bmc.core.model.Instance.LifecycleState lifecycleState = getLifecycleState();
            switch (lifecycleState) {
                case Running -> {
                    return true;
                }
                case Provisioning, Starting ->
                        LOG.info("Waiting for instance {} to start ({})...", id, lifecycleState);
                default -> throw new IllegalStateException("Unexpected lifecycle state: " + lifecycleState);
            }
            return false;
        }

        private com.oracle.bmc.core.model.Instance.LifecycleState getLifecycleState() {
            try {
                return GenericBenchmarkRunner.retry(() -> computeClient.getInstance(
                                GetInstanceRequest.builder()
                                        .instanceId(id)
                                        .build())
                        .getInstance()
                        .getLifecycleState());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public void awaitStartup() throws InterruptedException {
            while (true) {
                if (checkStarted()) {
                    return;
                }
                TimeUnit.SECONDS.sleep(5);
            }
        }

        public synchronized void terminateAsync() {
            LOG.info("Terminating compute instance {}", id);
            while (true) {
                Throttle.COMPUTE.takeUninterruptibly();
                try {
                    computeClient.terminateInstance(TerminateInstanceRequest.builder()
                            .instanceId(id)
                            .preserveBootVolume(false)
                            .build());
                    break;
                } catch (BmcException bmce) {
                    if (bmce.getStatusCode() == 429) {
                        LOG.info("429 while terminating compute instance {}, retrying in 30s", id);
                        try {
                            TimeUnit.SECONDS.sleep(30);
                        } catch (InterruptedException e) {
                            throw new RuntimeException(e);
                        }
                        continue;
                    }
                    throw bmce;
                }
            }
            terminating = true;
        }

        @Override
        public synchronized void close() {
            if (!terminating) {
                LOG.warn("Instance was not terminated before close() call");
                terminateAsync();
            }

            while (true) {
                Throttle.COMPUTE.takeUninterruptibly();
                com.oracle.bmc.core.model.Instance.LifecycleState lifecycleState = getLifecycleState();
                switch (lifecycleState) {
                    case Terminating -> {}
                    case Terminated -> {
                        return;
                    }
                    default -> throw new IllegalStateException("Unexpected state for compute instance " + id + ": " + lifecycleState + ". Instance should be terminating.");
                }
                LOG.info("Waiting for {} to terminate", id);
                try {
                    TimeUnit.SECONDS.sleep(5);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    @ConfigurationProperties("compute")
    public static class ComputeConfiguration {
        private Map<String, InstanceType> instanceTypes;

        public Map<String, InstanceType> getInstanceTypes() {
            return instanceTypes;
        }

        public void setInstanceTypes(Map<String, InstanceType> instanceTypes) {
            this.instanceTypes = instanceTypes;
        }

        @EachProperty("instanceTypes")
        public static class InstanceType {
            private String shape = "VM.Standard.E4.Flex";
            private float ocpus = 2.0f;
            private float memoryInGb = 8.0f;
            private String image = "Oracle-Linux-9.0-2022.12.16-0";

            public String getShape() {
                return shape;
            }

            public void setShape(String shape) {
                this.shape = shape;
            }

            public float getOcpus() {
                return ocpus;
            }

            public void setOcpus(float ocpus) {
                this.ocpus = ocpus;
            }

            public float getMemoryInGb() {
                return memoryInGb;
            }

            public void setMemoryInGb(float memoryInGb) {
                this.memoryInGb = memoryInGb;
            }

            public String getImage() {
                return image;
            }

            public void setImage(String image) {
                this.image = image;
            }
        }
    }
}
