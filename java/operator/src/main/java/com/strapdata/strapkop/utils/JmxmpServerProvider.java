package com.strapdata.strapkop.utils;

import io.micronaut.discovery.event.ServiceShutdownEvent;
import io.micronaut.discovery.event.ServiceStartedEvent;
import io.micronaut.runtime.event.annotation.EventListener;
import io.reactivex.Completable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Singleton
public class JmxmpServerProvider {

    private static final Logger logger = LoggerFactory.getLogger(JmxmpServerProvider.class);

    private JMXConnectorServer jmxServer = null;

    public void createJMXMPServer()  throws IOException
    {
        final int port = Optional.ofNullable(System.getenv("JMXMP_PORT")).map(Integer::parseInt).orElse(7200);

        Map<String, Object> env = new HashMap<>();
        // Mark the JMX server as a permanently exported object. This allows the JVM to exit with the
        // server running and also exempts it from the distributed GC scheduler which otherwise would
        // potentially attempt a full GC every `sun.rmi.dgc.server.gcInterval` millis (default is 3600000ms)
        // For more background see:
        //   - CASSANDRA-2967
        //   - https://www.jclarity.com/2015/01/27/rmi-system-gc-unplugged/
        //   - https://bugs.openjdk.java.net/browse/JDK-6760712
        env.put("jmx.remote.x.daemon", "true");
        JMXServiceURL url = new JMXServiceURL("jmxmp", null, port);
        jmxServer = JMXConnectorServerFactory.newJMXConnectorServer(url, env, ManagementFactory.getPlatformMBeanServer());

        jmxServer.start();

        logger.info("JMXMP started server="+url.toString());
        logger.debug("JMXMP env="+env);
    }

    public void close() {
        if (jmxServer != null) {
            try {
                jmxServer.stop();
            } catch (IOException e) {
                logger.warn("Error during JMXMP server stop : {}", e.getMessage());
            }
        }
    }

    @EventListener
    public void onStartEvent(final ServiceStartedEvent event) {
        try {
            createJMXMPServer();
        } catch (IOException e) {
            logger.warn("Unable to start JMXMP server : {}", e.getMessage(), e);
        }
    }

    @EventListener
    public void onStopEvent(final ServiceShutdownEvent event) {
        close();
    }


    public static void main(String[] args) throws IOException{
        /*JmxmpServerProvider jmxmpServerProvider = new JmxmpServerProvider();
        jmxmpServerProvider.createJMXMPServer();
        System.out.println("Waiting any character to stop...");
        System.in.read();
        jmxmpServerProvider.close();*/


        Completable.complete().andThen(Completable.fromAction(() -> {
            throw new RuntimeException("");
        })).andThen(Completable.fromAction(() -> {
            System.out.println("Pouey");
        })).doFinally(() -> System.err.println("Finally")).blockingGet();
    }
}
