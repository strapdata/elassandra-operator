package com.strapdata.strapkop.model.k8s;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.module.jsonSchema.JsonSchema;
import com.fasterxml.jackson.module.jsonSchema.factories.SchemaFactoryWrapper;
import com.strapdata.strapkop.model.fabric8.task.TaskSpec;
import com.strapdata.strapkop.model.fabric8.task.TaskStatus;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenterSpec;
import com.strapdata.strapkop.model.k8s.datacenter.DataCenterStatus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Path;

public class StrapdataCrdGroup {
    public static final String GROUP = "elassandra.strapdata.com";

    public static InputStream getDataCenterCrd() {
        return StrapdataCrdGroup.class.getResourceAsStream("/datacenter-crd.yaml");
    }

    public static InputStream getTaskCrd() {
        return StrapdataCrdGroup.class.getResourceAsStream("/task-crd.yaml");
    }

    public static void generateJsonSchema(Class clazz, Path output) throws IOException {
        ObjectMapper m = new ObjectMapper();

        SchemaFactoryWrapper visitor = new SchemaFactoryWrapper();
        m.acceptJsonFormatVisitor(m.constructType(clazz), visitor);
        JsonSchema jsonSchema = visitor.finalSchema();

        ObjectMapper mapper = new ObjectMapper(new JsonFactory());
        mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
        mapper.configure(SerializationFeature.WRITE_ENUMS_USING_TO_STRING, true);
        mapper.writeValue(output.toFile(), jsonSchema);
    }

    public static void main(String[] args) {
        try {
            generateJsonSchema(DataCenterSpec.class, FileSystems.getDefault().getPath("docs/source/datacenter-spec.json"));
            generateJsonSchema(DataCenterStatus.class, FileSystems.getDefault().getPath("docs/source/datacenter-status.json"));
            generateJsonSchema(TaskSpec.class, FileSystems.getDefault().getPath("docs/source/task-spec.json"));
            generateJsonSchema(TaskStatus.class, FileSystems.getDefault().getPath("docs/source/task-status.json"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
