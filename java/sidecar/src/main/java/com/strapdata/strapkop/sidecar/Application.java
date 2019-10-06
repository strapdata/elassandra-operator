package com.strapdata.strapkop.sidecar;

import io.micronaut.runtime.Micronaut;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.tags.Tag;

@OpenAPIDefinition(
        info = @Info(
                title = "Strapkop sidecar",
                version = "0.1",
                description = "Strapdata Elassandra sidecar",
                license = @License(name = "Strapdata license", url = "http://petstore.notreal")
        ),
        tags = {
                @Tag(name = "backups"),
                @Tag(name = "operations"),
                @Tag(name = "status"),
                @Tag(name = "search"),
                @Tag(name = "logs")
        }
)
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