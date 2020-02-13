package com.strapdata.strapkop.model;

import io.kubernetes.client.models.V1ObjectMeta;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@Data
public class Key {
    public final String namespace;
    public final String name;

    public Key() {
        namespace = null;
        name = null;
    }

    public Key(String name, String namespace) {
        this.name = name;
        this.namespace = namespace;
    }

    public Key(final V1ObjectMeta metadata) {
        this.name = metadata.getName();
        this.namespace = metadata.getNamespace();
    }
}
