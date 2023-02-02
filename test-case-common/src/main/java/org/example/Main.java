package org.example;

import io.micronaut.context.event.StartupEvent;
import io.micronaut.runtime.Micronaut;
import io.micronaut.runtime.event.annotation.EventListener;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class Main {
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        Micronaut.run(args);
    }

    @EventListener
    protected void onStartup(StartupEvent event) {
        LOGGER.info("Running on Java {}", System.getProperty("java.version"));
    }
}
