package com.strapdata.strapkop.backup.common;

import java.nio.file.Path;

public class AWSRemoteObjectReference extends RemoteObjectReference {
    public AWSRemoteObjectReference(Path objectKey, String canonicalPath) {
        super(objectKey, canonicalPath);
    }

    @Override
    public Path getObjectKey() {
        return objectKey;
    }
}
