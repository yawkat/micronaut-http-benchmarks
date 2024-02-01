package org.example;

import io.netty.util.ResourceLeakDetector;

public class Main {
    @SuppressWarnings("resource")
    public static void main(String[] args) throws Exception {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);

        HttpServer httpServer = new HttpServer();
        httpServer.bindHttp("0.0.0.0", 8080);
        System.out.println("Bound to http://0.0.0.0:8080");
        httpServer.bindHttps("0.0.0.0", 8443);
        System.out.println("Bound to https://0.0.0.0:8443");
    }
}