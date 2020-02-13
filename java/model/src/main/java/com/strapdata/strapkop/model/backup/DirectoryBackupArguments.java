package com.strapdata.strapkop.model.backup;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.spi.StringArrayOptionHandler;

import java.io.PrintStream;
import java.util.List;

public class DirectoryBackupArguments extends CommonBackupArguments {
    public DirectoryBackupArguments(final String appName, final PrintStream stream) {
        super(appName, stream);
    }

    @Override
    void commonBackupPrintHelp() {
        stream.println("Take a com.com.backup of directories provided as arguments. This will be uploaded to remote storage");
    }

    @Argument(index = 0, metaVar = "rootLabel", usage = "Name of the com.com.backup root Directory", required = true)
    public String rootLabel;

    @Argument(index = 1, metaVar = "directory", usage = "List of source directories to com.com.backup", handler = StringArrayOptionHandler.class, required = true)
    public List<String> sources;
}
