package com.strapdata.strapkop.model.k8s;


import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;

import java.io.InputStream;

@OpenAPIDefinition(
        info = @Info(
                title = "Elassandra-Operator CRD",
                version = "1.0",
                description = "Elassandra-Operator Custom Resource Definition",
                contact = @Contact(url = "http://www.strapdata.com", name = "Fred", email = "support@strapdata.com")
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
