package com.muse.connect.config;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ensures the persistent data volume subdirectories (SQLite directory & logs directory) exist
 * before HikariCP/SQLite opens the database file.
 */
@Component
public class DataDirectoryInitializer implements BeanFactoryPostProcessor, EnvironmentAware {

    private static final String SQLITE_PREFIX = "jdbc:sqlite:";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (environment == null) {
            return;
        }
        createDirectoryIfConfigured(environment.getProperty("muse.data.dir"));
        createDirectoryIfConfigured(environment.getProperty("muse.data.sqlite-dir"));
        createDirectoryIfConfigured(environment.getProperty("muse.data.log-dir"));

        String jdbcUrl = environment.getProperty("spring.datasource.url");
        if (StringUtils.hasText(jdbcUrl) && jdbcUrl.startsWith(SQLITE_PREFIX)) {
            String target = jdbcUrl.substring(SQLITE_PREFIX.length());
            int queryIdx = target.indexOf('?');
            if (queryIdx >= 0) {
                target = target.substring(0, queryIdx);
            }
            if (!target.isBlank() && !":memory:".equals(target) && !target.startsWith("file::memory:")) {
                if (target.startsWith("file:")) {
                    target = target.substring("file:".length());
                }
                Path parent = Path.of(target).toAbsolutePath().normalize().getParent();
                if (parent != null) {
                    createDirectory(parent);
                }
            }
        }
    }

    private void createDirectoryIfConfigured(String dir) {
        if (StringUtils.hasText(dir)) {
            createDirectory(Path.of(dir.trim()).toAbsolutePath().normalize());
        }
    }

    private void createDirectory(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create data directory: " + path, ex);
        }
    }
}
