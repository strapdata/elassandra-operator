package com.strapdata.gradle;

import org.codehaus.plexus.util.IOUtil;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ReallyExecutableJarTask extends org.gradle.api.DefaultTask {

    @TaskAction
    public void run() {
        try {
            Project project = getProject();
            File jarfile = new File(String.format("%s/libs/%s.jar", project.getBuildDir(), project.getName()));
            File exefile = new File(String.format("%s/libs/%s", project.getBuildDir(), project.getName()));

            /**
             * Shell script to add to the jar instead of the default stanza.
             */
            String scriptFile = null;

            /**
             * Java command line arguments to embed. Only used with the default stanza.
             */
            String flags = "-Dmicronaut.environments=$MICRONAUT_ENV";

            System.out.println("Making " + exefile.toPath() + " executable");

            try (final FileOutputStream out = new FileOutputStream(exefile);
                 final InputStream in = Files.newInputStream(jarfile.toPath())) {

                if (scriptFile == null) {
                    out.write(("#!/bin/sh\n\nexec java " + flags + " -jar \"$0\" \"$@\"\n\n").getBytes("ASCII"));
                } else if (Files.exists(Paths.get(scriptFile))) {
                    System.out.println(String.format("Loading file[%s] from filesystem", scriptFile));

                    byte[] script = Files.readAllBytes(Paths.get(scriptFile));
                    out.write(script);
                    out.write(new byte[]{'\n', '\n'});
                } else {
                    System.out.println(String.format("Loading file[%s] from jar[%s]", scriptFile, jarfile));

                    try (final URLClassLoader loader = new URLClassLoader(new URL[]{jarfile.toPath().toUri().toURL()}, null);
                         final InputStream scriptIn = loader.getResourceAsStream(scriptFile)) {

                        out.write(IOUtil.toString(scriptIn).getBytes("ASCII"));
                        out.write("\n\n".getBytes("ASCII"));
                    }
                }
                IOUtil.copy(in, out);
            }
            exefile.setExecutable(true, false);
            System.out.println(String.format("Successfully made JAR [%s] executable", exefile.getAbsolutePath()));
        } catch (Exception e) {
            System.err.println("error:" + e.getMessage());
            e.printStackTrace(System.err);
        }
    }
}
