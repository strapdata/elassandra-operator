package com.strapdata.strapkop;

import io.micronaut.runtime.Micronaut;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.tags.Tag;

@OpenAPIDefinition(
        info = @Info(
                title = "Strapkop operator",
                version = "0.1",
                description = "Strapdata Elassandra operator"
                //license = @License(name = "Strapdata license", url = "http://petstore.notreal")
        ),
        tags = {
                @Tag(name = "operator")
        }
)
public class Application {
    public static void main(String[] args) {
        Micronaut.run(Application.class);
    }
}