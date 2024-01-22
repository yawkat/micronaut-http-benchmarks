package org.example;

import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

public class Main {
    public static void main(String[] args) {
        int nThreads = Runtime.getRuntime().availableProcessors();
        Vertx.vertx(new VertxOptions()
                        .setEventLoopPoolSize(nThreads)
                        .setPreferNativeTransport(true))
                .deployVerticle(MyVerticle.class, new DeploymentOptions().setInstances(nThreads)).andThen(r -> {
            if (r.failed()) {
                r.cause().printStackTrace();
                System.exit(0);
            } else {
                System.out.println("Vertx bound");
            }
        });
    }
}