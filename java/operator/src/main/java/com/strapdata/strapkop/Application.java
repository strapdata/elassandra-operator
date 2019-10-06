package com.strapdata.strapkop;

import io.micronaut.runtime.Micronaut;

/*
@OpenAPIDefinition(
        info = @Info(
                title = "Strapkop operator",
                version = "0.1",
                description = "Strapdata Elassandra operator",
                license = @License(name = "Strapdata license", url = "http://petstore.notreal")
        ),
        tags = {
                @Tag(name = "seeds")
        }
)
 */
public class Application {
    public static void main(String[] args) {
        Micronaut.run(Application.class);
    }
}