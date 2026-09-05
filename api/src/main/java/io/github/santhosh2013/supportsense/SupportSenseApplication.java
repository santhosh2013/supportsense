package io.github.santhosh2013.supportsense;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SupportSenseApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupportSenseApplication.class, args);
    }
}
