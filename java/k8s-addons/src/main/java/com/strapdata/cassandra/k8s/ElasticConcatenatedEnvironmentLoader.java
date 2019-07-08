package com.strapdata.cassandra.k8s;


import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.cassandra.exceptions.ConfigurationException;
import org.elassandra.env.EnvironmentLoader;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsException;
import org.elasticsearch.env.Environment;
import org.elasticsearch.node.InternalSettingsPreparer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Splitter;

// TODO: could be refactored with ConcatenatedYamlConfigurationLoader to avoid duplicate code
public class ElasticConcatenatedEnvironmentLoader implements EnvironmentLoader  {
    private static final Logger logger = LoggerFactory.getLogger(ElasticConcatenatedEnvironmentLoader.class);
    
    private static final PathMatcher YAML_PATH_MATCHER = FileSystems.getDefault().getPathMatcher("glob:**/*.{yaml,yml}");
    
    @Override
    public Environment loadEnvironment(boolean foreground, String homeDir, String configDir) {
        final String configProperty = System.getProperty("elasticsearch.config");
        logger.info("Loading elasticsearch config from {}", configProperty);
        
        final Iterable<String> configValues = Splitter.on(':').split(configProperty);
        final List<Path> paths = StreamSupport.stream(configValues.spliterator(), false)
                .map(Paths::get)
                
                // recurse into any specified directories and load any config files within
                .flatMap(path -> {
                    if (!Files.exists(path)) {
                        logger.warn("Specified configuration file/directory {} does not exist.", path);
                        return Stream.empty();
                    }
                    
                    if (Files.isDirectory(path)) {
                        try {
                            return Files.list(path)
                                    .sorted();
                            
                        } catch (final IOException e) {
                            throw new ConfigurationException(String.format("Failed to open directory \"%s\".", path), e);
                        }
                        
                    } else {
                        return Stream.of(path);
                    }
                })
                // only load regular yaml files
                .filter(path -> {
                    if (!Files.isRegularFile(path)) {
                        logger.warn("Configuration file \"{}\" is not a regular file and will not be loaded.", path);
                        return false;
                    }
                    if (!YAML_PATH_MATCHER.matches(path)) {
                        logger.warn("Configuration file \"{}\" is not a YAML file and will not be loaded.", path);
                        return false;
                    }
                    logger.info("Loading configuration file \"{}\"", path);
                    return true;
                })
                .collect(Collectors.toList());
        
        // Load in revers order because setting cannot be overwritten
        Collections.reverse(paths);
        
        Settings settings = Settings.builder()
                .put("path.home", homeDir)
                .build();
        for(Path path : paths) {
            Settings.Builder output = Settings.builder();
            if (Files.exists(path)) {
                try {
                    output.loadFromPath(path);
                } catch (IOException e) {
                    throw new SettingsException("Failed to load settings from " + path.toString(), e);
                }
            }
            output.put(settings);
            output.replacePropertyPlaceholders();
            settings = output.build();
            logger.debug("env path=" + path + " settings=" + settings);
        }
        return InternalSettingsPreparer.prepareEnvironment(settings, null, Collections.EMPTY_MAP, Paths.get(configDir));
    }
}
