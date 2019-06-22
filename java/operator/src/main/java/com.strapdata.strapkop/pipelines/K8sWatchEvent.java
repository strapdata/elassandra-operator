package com.strapdata.strapkop.pipelines;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class K8sWatchEvent<ResourceT> {

    public enum Type {
        ADDED,
        MODIFIED,
        DELETED,
        ERROR,
        INITIAL
    }
    
    private Type type;
    private ResourceT resource;
}