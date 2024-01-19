package io.micronaut.benchmark.loadgen.oci;

import com.oracle.bmc.auth.AbstractAuthenticationDetailsProvider;
import com.oracle.bmc.common.RegionalClientBuilder;
import com.oracle.bmc.core.ComputeClient;
import com.oracle.bmc.core.VirtualNetworkClient;
import com.oracle.bmc.identity.IdentityClient;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

import java.util.HashMap;
import java.util.Map;

public interface RegionalClient<C> {
    C forRegion(OciLocation location);

    @Factory
    @Singleton
    class RegionalFactory {
        private final AbstractAuthenticationDetailsProvider authenticationDetailsProvider;

        RegionalFactory(AbstractAuthenticationDetailsProvider authenticationDetailsProvider) {
            this.authenticationDetailsProvider = authenticationDetailsProvider;
        }

        private <C> RegionalClient<C> clientFor(RegionalClientBuilder<? extends RegionalClientBuilder<?, C>, C> builder) {
            return new RegionalClient<>() {
                private final Map<String, C> cache = new HashMap<>();

                @Override
                public synchronized C forRegion(OciLocation location) {
                    return cache.computeIfAbsent(location.region(), reg -> builder.region(reg).build(authenticationDetailsProvider));
                }
            };
        }

        @Singleton
        RegionalClient<ComputeClient> compute(ComputeClient.Builder builder) {
            return clientFor(builder);
        }

        @Singleton
        RegionalClient<VirtualNetworkClient> network(VirtualNetworkClient.Builder builder) {
            return clientFor(builder);
        }

        @Singleton
        RegionalClient<IdentityClient> identity(IdentityClient.Builder builder) {
            return clientFor(builder);
        }
    }
}
