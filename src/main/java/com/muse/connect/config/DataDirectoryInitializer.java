package com.muse.connect.config;

import com.muse.connect.constant.ExceptionMessageConstants;
import com.muse.connect.constant.StorageConstants;
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
 * 在 HikariCP/SQLite 打开数据库文件之前，确保持久化数据卷下的子目录（SQLite 目录与日志目录）均已创建。
 */
@Component
public class DataDirectoryInitializer implements BeanFactoryPostProcessor, EnvironmentAware {

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
        createDirectoryIfConfigured(environment.getProperty(StorageConstants.PROP_DATA_DIR));
        createDirectoryIfConfigured(environment.getProperty(StorageConstants.PROP_SQLITE_DIR));
        createDirectoryIfConfigured(environment.getProperty(StorageConstants.PROP_LOG_DIR));

        String jdbcUrl = environment.getProperty(StorageConstants.PROP_DATASOURCE_URL);
        if (StringUtils.hasText(jdbcUrl) && jdbcUrl.startsWith(StorageConstants.SQLITE_JDBC_PREFIX)) {
            String target = jdbcUrl.substring(StorageConstants.SQLITE_JDBC_PREFIX.length());
            int queryIdx = target.indexOf('?');
            if (queryIdx >= 0) {
                target = target.substring(0, queryIdx);
            }
            if (!target.isBlank()
                    && !StorageConstants.SQLITE_MEMORY_TARGET.equals(target)
                    && !target.startsWith(StorageConstants.SQLITE_FILE_MEMORY_PREFIX)) {
                if (target.startsWith(StorageConstants.SQLITE_FILE_PREFIX)) {
                    target = target.substring(StorageConstants.SQLITE_FILE_PREFIX.length());
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
            throw new IllegalStateException(ExceptionMessageConstants.DATA_DIR_CREATE_FAILED_PREFIX + path, ex);
        }
    }
}
