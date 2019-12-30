package com.strapdata.strapkop.reconcilier;

import com.google.common.util.concurrent.Monitor;
import io.micrometer.core.instrument.MeterRegistry;
import io.reactivex.Completable;
import io.reactivex.functions.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manage gracefull stop, waiting ongoing reconciliation to finish.
 * Register custom metrics for monitoring.
 */
@Singleton
public class ReconcilierObserver {

    private final Logger logger = LoggerFactory.getLogger(ReconcilierObserver.class);

    @Inject
    private MeterRegistry meterRegistry;

    Integer count;
    volatile boolean gracefullStop = false;

    private final Monitor monitor = new Monitor();

    private final Monitor.Guard countIsZero = new Monitor.Guard(monitor) {
        public boolean isSatisfied() {
            return count == 0;
        }
    };

    private final Monitor.Guard gracefullStopFalse = new Monitor.Guard(monitor) {
        public boolean isSatisfied() {
            return gracefullStop == false;
        }
    };

    public ReconcilierObserver(MeterRegistry meterRegistry) {
        count = Integer.valueOf(0);
        this.meterRegistry = meterRegistry;
        meterRegistry.gauge("reconciliation.current", count);
        meterRegistry.counter("reconciliation.begin");
        meterRegistry.counter("reconciliation.end");
        meterRegistry.counter("reconciliation.failed");
    }

    public synchronized boolean beginReconciliation() {
        if (monitor.tryEnterIf(gracefullStopFalse)) {
            try {
                count++;
                meterRegistry.counter("reconciliation.begin").increment();
                meterRegistry.gauge("reconciliation.current", count);
                logger.debug("Reconciliation begin, count={}", count);
                return true;
            } finally {
                monitor.leave();
            }
        } else {
            return false;
        }
    }


    public synchronized void endReconciliation() {
        monitor.enter();
        try {
            count--;
            meterRegistry.counter("reconciliation.end").increment();
            meterRegistry.gauge("reconciliation.current", count);
            logger.debug("Reconciliation end, count={}", count);
        } finally {
            monitor.leave();
        }
    }

    public synchronized void failedReconciliation() {
        monitor.enter();
        try {
            count--;
            meterRegistry.counter("reconciliation.end").increment();
            meterRegistry.counter("reconciliation.failed").increment();
            meterRegistry.gauge("reconciliation.current", count);
            logger.debug("Reconciliation failed, count={}", count);
        } finally {
            monitor.leave();
        }
    }

    public Completable onReconciliationBegin() {
        if (beginReconciliation())
            return Completable.complete();
        throw new ReconcilierShutdownException("Gracefull shutdown");
    }

    public Completable onReconciliationEnd() {
        endReconciliation();
        return Completable.complete();
    }

    public Completable onReconciliationFailed() {
        failedReconciliation();
        return Completable.complete();
    }

    public Action endReconciliationAction() {
        return new Action() {
            @Override
            public void run() throws Exception {
                endReconciliation();
            }
        };
    }

    public Action failedReconciliationAction() {
        return new Action() {
            @Override
            public void run() throws Exception {
                failedReconciliation();
            }
        };
    }

    public void gracefullStop() throws InterruptedException {
        monitor.enterWhen(countIsZero);
        try {
            gracefullStop = false;
            logger.info("Gracefull stopping");
        } finally {
            monitor.leave();
        }
    }
}
