package org.example;

import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;

public class Main {
    public static void main(String[] args) {
        Vertx.vertx(new VertxOptions().setPreferNativeTransport(true)).deployVerticle(new MyVerticle(8080, 8443)).andThen(r -> {
            if (r.failed()) {
                r.cause().printStackTrace();
                System.exit(0);
            } else {
                System.out.println("Vertx bound");
            }
        });
    }
}