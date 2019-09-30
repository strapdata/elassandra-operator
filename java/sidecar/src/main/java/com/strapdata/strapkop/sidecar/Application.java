package com.strapdata.strapkop.sidecar;

import io.micronaut.runtime.Micronaut;

/*
@OpenAPIDefinition(
        info = @Info(
                title = "Strapkop",
                version = "0.1",
                description = "Strapdata Kuberenetes Operator for Elassandra",
                license = @License(name = "Strapdata license", url = "http://petstore.notreal")
        ),
        tags = {
                @Tag(name = "backups"),
                @Tag(name = "operations"),
                @Tag(name = "status"),
                @Tag(name = "search"),
                @Tag(name = "gclog")
        }
)
 */
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