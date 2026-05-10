package com.hello.botsimulator;

import com.hello.botsimulator.config.SimulatorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(SimulatorProperties.class)
public class BotSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(BotSimulatorApplication.class, args);
    }
}
