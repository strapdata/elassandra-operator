package com.strapdata.strapkop.sidecar;

import io.micronaut.runtime.Micronaut;

public class Application {

    public static void main(String[] args) {
        // wait 30 sec so that elassandra has time to start
        try {
            Thread.sleep(30 * 1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        Micronaut.run(Application.class);
    }
}