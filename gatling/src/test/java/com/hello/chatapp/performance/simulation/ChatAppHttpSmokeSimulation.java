package com.hello.chatapp.performance.simulation;

import com.hello.chatapp.performance.config.PerformanceTestConfig;
import com.hello.chatapp.performance.scenario.HttpSmokeScenario;
import io.gatling.javaapi.core.Simulation;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.global;

/**
 * First runnable Gatling simulation for the chat application.
 *
 * <p>This is intentionally small: it proves the standalone Maven project,
 * feeder loading, session-cookie reuse, and HTML report generation.
 */
public class ChatAppHttpSmokeSimulation extends Simulation {

    public ChatAppHttpSmokeSimulation() {
        setUp(
                HttpSmokeScenario.smoke()
                        .injectOpen(atOnceUsers(PerformanceTestConfig.smokeUsers()))
        ).protocols(HttpSmokeScenario.httpProtocol())
                .assertions(
                        global().failedRequests().percent().lt(1.0),
                        global().responseTime().max().lt(5000)
                );
    }
}
