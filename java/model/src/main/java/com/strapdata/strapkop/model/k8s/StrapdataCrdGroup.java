package com.strapdata.strapkop.model.k8s;


import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;

import java.io.InputStream;

@OpenAPIDefinition(
        info = @Info(
                title = "Elassandra-Operator CRD",
                version = "1.0",
                description = "Elassandra-Operator Custom Resource Definition",
                license = @License(name = "Apache 2.0", url = "http://foo.bar"),
                contact = @Contact(url = "http://gigantic-server.com", name = "Fred", email = "support@strapdata.com")
        )
)
public class StrapdataCrdGroup {
    public static final String GROUP = "elassandra.strapdata.com";

    public static InputStream getDataCenterCrd() {
        return StrapdataCrdGroup.class.getResourceAsStream("/datacenter-crd.yaml");
    }

    public static InputStream getTaskCrd() {
        return StrapdataCrdGroup.class.getResourceAsStream("/task-crd.yaml");
    }
}
