package com.strapdata.strapkop;

import io.micronaut.configuration.picocli.PicocliRunner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "edctl",
        description = "Elassandra Datacenter Cli",
        mixinStandardHelpOptions = true,
        subcommands = { WatchCommand.class })
public class Edctl {

    @Option(names = {"-v", "--verbose"}, description = "Verbose mode")
    boolean verbose;

    public static void main(String[] args) {
        System.exit(PicocliRunner.execute(Edctl.class, args));
    }
}
