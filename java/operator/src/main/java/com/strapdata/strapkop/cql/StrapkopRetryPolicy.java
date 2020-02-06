package com.strapdata.strapkop.cql;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.WriteType;
import com.datastax.driver.core.exceptions.DriverException;
import com.datastax.driver.core.policies.DefaultRetryPolicy;
import com.datastax.driver.core.policies.RetryPolicy;

public class StrapkopRetryPolicy implements RetryPolicy {

    private static final RetryPolicy DEFAULT_RETRY = DefaultRetryPolicy.INSTANCE;
    public static final RetryPolicy INSTANCE = new StrapkopRetryPolicy();

    @Override
    public RetryDecision onReadTimeout(Statement statement, ConsistencyLevel cl, int requiredResponses, int receivedResponses, boolean dataRetrieved, int nbRetry) {
        return DEFAULT_RETRY.onReadTimeout(statement, cl, requiredResponses, receivedResponses, dataRetrieved, nbRetry);
    }

    @Override
    public RetryDecision onWriteTimeout(Statement statement, ConsistencyLevel cl, WriteType writeType, int requiredAcks, int receivedAcks, int nbRetry) {
        return DEFAULT_RETRY.onWriteTimeout(statement, cl, writeType, requiredAcks, receivedAcks, nbRetry);
    }

    @Override
    public RetryDecision onUnavailable(Statement statement, ConsistencyLevel cl, int requiredReplica, int aliveReplica, int nbRetry) {
        if (nbRetry != 0) return RetryDecision.rethrow();
        if (cl.isSerial()) return RetryDecision.rethrow();
        // try local_one to avoid blocking reconcile
        return RetryDecision.tryNextHost(ConsistencyLevel.LOCAL_ONE);
    }

    @Override
    public RetryDecision onRequestError(Statement statement, ConsistencyLevel cl, DriverException e, int nbRetry) {
        return DEFAULT_RETRY.onRequestError(statement, cl, e, nbRetry);
    }

    @Override
    public void init(Cluster cluster) {
        // nothing to do
    }

    @Override
    public void close() {
        // nothing to do
    }
}
