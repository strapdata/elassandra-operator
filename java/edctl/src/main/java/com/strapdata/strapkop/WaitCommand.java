package com.strapdata.strapkop;

import com.strapdata.strapkop.model.k8s.cassandra.DataCenterPhase;
import picocli.CommandLine;

import javax.inject.Inject;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@CommandLine.Command(name = "wait", description = "edctl wait subcommand")
public class WaitCommand implements Callable<Object> {

    @Inject
    DataCenterPipeline dataCenterPipeline;

    @CommandLine.Option(names = {"-p","--phase"}, description = "Elassandra datacenter phase")
    DataCenterPhase phase = null;

    @CommandLine.Option(names = {"-t","--timeout"}, description = "Wait timeout")
    Duration timeout = null;

    @Override
    public Object call() throws Exception {
        if (phase != null) {
            System.out.println("Waiting phase=" + phase);
            CountDownLatch countDownLatch = new CountDownLatch(1);
            dataCenterPipeline.start();
            dataCenterPipeline.subscribe(new DataCenterHandler(countDownLatch, phase));

            if (timeout != null) {
                if (countDownLatch.await(timeout.getSeconds(), TimeUnit.SECONDS)) {
                    System.exit(0);
                }
            }
            try {
                countDownLatch.await();
                System.exit(0);
            } catch(InterruptedException e) {
            }
            System.exit(1);
        }
        return 0;
    }


}
