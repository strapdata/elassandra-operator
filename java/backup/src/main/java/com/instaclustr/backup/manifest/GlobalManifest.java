package com.instaclustr.backup.manifest;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Getter
public class GlobalManifest {
    private final String dataCenter;
    private final String snapshotTag;

    private Map<String, String> perNodeManifest = new ConcurrentHashMap<>();

    public GlobalManifest(String dataCenter, String snapshotTag) {
        this.dataCenter = dataCenter;
        this.snapshotTag = snapshotTag;
    }

    public void addManifest(String node, String manifestPath) {
        this.perNodeManifest.put(node, manifestPath);
    }

    public Set<String> getNodes() {
        return this.perNodeManifest.keySet();
    }

    public List<String> getManifestPaths() {
        return perNodeManifest.entrySet().stream()
                .map(entry -> dataCenter+"/"+entry.getKey()+"/"+entry.getValue())
                .collect(Collectors.toList());
    }
}
