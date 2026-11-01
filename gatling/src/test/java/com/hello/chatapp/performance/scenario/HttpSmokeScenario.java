package com.hello.chatapp.performance.scenario;

import com.hello.chatapp.performance.config.PerformanceTestConfig;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * Small HTTP-only scenario used to prove the Gatling project shape and session
 * handling before adding more complex workloads.
 */
public final class HttpSmokeScenario {

    private HttpSmokeScenario() {
    }

    /**
     * Shared HTTP protocol setup for the starter simulations.
     */
    public static HttpProtocolBuilder httpProtocol() {
        return http
                .baseUrl(PerformanceTestConfig.baseUrl())
                .acceptHeader("application/json")
                .contentTypeHeader("application/json")
                .userAgentHeader("chat-app-gatling-smoke");
    }

    /**
     * Smoke flow: login, validate the session, load groups, then load one
     * group's history page.
     */
    public static ScenarioBuilder smoke() {
        return scenario("chat-app-http-smoke")
                .feed(csv("feeders/test-users.csv").circular())
                .exec(http("auth-login")
                        .post("/api/auth/login")
                        .body(StringBody("{\"username\":\"#{username}\",\"password\":\"#{password}\"}"))
                        .check(status().is(200)))
                .pause(PerformanceTestConfig.pauseMillis())
                .exec(http("auth-check")
                        .get("/api/auth/check")
                        .check(status().is(200)))
                .pause(PerformanceTestConfig.pauseMillis())
                .exec(http("groups-list")
                        .get("/api/groups")
                        .check(status().is(200)))
                .pause(PerformanceTestConfig.pauseMillis())
                .exec(http("group-history")
                        .get("/api/messages/groups/#{groupId}")
                        .check(status().is(200)));
    }
}
