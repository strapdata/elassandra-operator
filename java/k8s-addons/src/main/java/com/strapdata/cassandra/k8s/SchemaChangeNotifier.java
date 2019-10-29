package com.strapdata.cassandra.k8s;

import org.apache.cassandra.schema.KeyspaceMetadata;
import org.apache.cassandra.service.MigrationListener;

/**
 * Catch CQL keyspace changes and notify strapkop to adjust RF
 */
public class SchemaChangeNotifier extends MigrationListener {

    public void onCreateKeyspace(KeyspaceMetadata ksm)
    {

    }

    public void onUpdateKeyspace(KeyspaceMetadata ksm)
    {

    }

    public void onDropKeyspace(KeyspaceMetadata ksm)
    {

    }
}
