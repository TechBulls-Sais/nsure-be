package com.nsure;

import com.nsure.config.AuditProperties;
import com.nsure.config.MakerCheckerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AuditProperties.class, MakerCheckerProperties.class})
public class NsureApplication {

    public static void main(String[] args) {
        SpringApplication.run(NsureApplication.class, args);
    }
}
