package com.hello.mediaprocessing;

import io.micronaut.runtime.Micronaut;

/**
 * Bootstraps the media-processing Micronaut application.
 */
public class Application {

    /**
     * Starts the Micronaut runtime for the media-processing service.
     *
     * @param args command-line arguments passed to the JVM process
     */
    public static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}