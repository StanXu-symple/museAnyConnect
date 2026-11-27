package com.muse.connect;

import com.muse.connect.config.AppProperties;
import com.muse.connect.config.AuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AppProperties.class, AuthProperties.class})
public class MuseAnyConnectApplication {

    public static void main(String[] args) {
        SpringApplication.run(MuseAnyConnectApplication.class, args);
    }
}
