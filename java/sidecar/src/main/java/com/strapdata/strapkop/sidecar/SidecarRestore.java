package com.strapdata.strapkop.sidecar;


import com.strapdata.strapkop.backup.task.RestoreTask;
import com.strapdata.strapkop.backup.util.GlobalLock;
import com.strapdata.strapkop.model.backup.RestoreArguments;
import org.apache.commons.codec.digest.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@CommandLine.Command(name = "cassandra-restore",
        description = "Sidecar management application for Apache Cassandra running on Kubernetes."
)
public class SidecarRestore implements Callable<Void> {
    private static final Logger logger = LoggerFactory.getLogger(SidecarRestore.class);

    private final static String RESTORE_PROOF_PREFIX = "/var/lib/cassandra/restored";

    @CommandLine.Unmatched
    private String[] args = new String[0];
    
    @CommandLine.Spec
    private CommandLine.Model.CommandSpec commandSpec;

    @SuppressWarnings( "deprecation" )
    public static void main(final String[] args) throws IOException {
        CommandLine.call(new SidecarRestore(), System.err, CommandLine.Help.Ansi.ON, args);
    }
    
    @Override
    public Void call() throws Exception {
        
        final RestoreArguments arguments = new RestoreArguments("cassandra-restore", System.err);
        arguments.parseArguments(args);
        
        Pattern p = Pattern.compile("(\\d+$)");
        Matcher m = p.matcher(String.join("", Files.readAllLines(Paths.get("/etc/podinfo/name"))));
        m.find();
        String ordinal = m.group();
        
        logger.info("detected ordinal is {}", ordinal);
        
        try {
            File restoredProof = Paths.get(RESTORE_PROOF_PREFIX+"-"+DigestUtils.sha1Hex(arguments.snapshotTag)).toFile();
            if (restoredProof.exists()) {
                logger.info("Restore already done.");
            } else {
                GlobalLock globalLock = new GlobalLock("/tmp");
                arguments.sourceNodeID = arguments.sourceNodeID + "-" + ordinal; //make getting the ordinal more robust
                boolean done = new RestoreTask(
                        globalLock,
                        arguments
                ).call();

                if (done) {
                    logger.info("Restore completed successfully.");
                    logger.info("Proof file created to avoid cleanup & restore on pod restart : {}", restoredProof.createNewFile());
                } else {
                    logger.warn("RestoreTask not executed...");
                }
            }

            System.exit(0);
        } catch (final Exception e) {
            logger.error("Failed to complete restore.", e);
            System.exit(1);
        }
        return null;
    }
}