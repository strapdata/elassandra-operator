package com.strapdata.strapkop.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.EnumSet;

@Data
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class K8sWatchEvent<ResourceT> {

    public static final EnumSet<Type> creationEventTypes = EnumSet.of(Type.ADDED, Type.MODIFIED, Type.INITIAL);
    public static final EnumSet<Type> deletionEventTypes = EnumSet.of(Type.DELETED);

    public enum Type {
        ADDED,
        MODIFIED,
        DELETED,
        ERROR,
        INITIAL
    }

    private Type type;
    private ResourceT resource;
    private String lastResourceVersion = null;

    public boolean isUpdate() {
        return creationEventTypes.contains(this.type);
    }

    public boolean isDeletion() {
        return deletionEventTypes.contains(this.type);
    }

}