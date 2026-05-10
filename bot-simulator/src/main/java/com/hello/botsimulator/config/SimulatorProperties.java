package com.hello.botsimulator.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "simulator")
public class SimulatorProperties {

    @NotBlank
    private String baseUrl = "http://localhost:9010";

    @NotBlank
    private String wsEndpoint = "/ws";

    @Min(1)
    private int botCount = 1000;

    @NotBlank
    private String botUsernamePrefix = "u";

    @NotBlank
    private String botPassword = "5555";

    @NotEmpty
    private List<Long> targetGroupIds = new ArrayList<>(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L));

    @NotBlank
    private String messagePrefix = "Load test";

    @Min(1)
    private long sendIntervalMs = 500;

    @Min(0)
    private long sendJitterMs = 250;

    @Min(0)
    private long startupSpreadMs = 15000;

    @Min(100)
    private long reconnectDelayMs = 1500;

    @Min(1)
    private int connectTimeoutSeconds = 10;

    @Min(1)
    private int reportIntervalSeconds = 5;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getWsEndpoint() {
        return wsEndpoint;
    }

    public void setWsEndpoint(String wsEndpoint) {
        this.wsEndpoint = wsEndpoint;
    }

    public int getBotCount() {
        return botCount;
    }

    public void setBotCount(int botCount) {
        this.botCount = botCount;
    }

    public String getBotUsernamePrefix() {
        return botUsernamePrefix;
    }

    public void setBotUsernamePrefix(String botUsernamePrefix) {
        this.botUsernamePrefix = botUsernamePrefix;
    }

    public String getBotPassword() {
        return botPassword;
    }

    public void setBotPassword(String botPassword) {
        this.botPassword = botPassword;
    }

    public List<Long> getTargetGroupIds() {
        return targetGroupIds;
    }

    public void setTargetGroupIds(List<Long> targetGroupIds) {
        this.targetGroupIds = targetGroupIds;
    }

    public String getMessagePrefix() {
        return messagePrefix;
    }

    public void setMessagePrefix(String messagePrefix) {
        this.messagePrefix = messagePrefix;
    }

    public long getSendIntervalMs() {
        return sendIntervalMs;
    }

    public void setSendIntervalMs(long sendIntervalMs) {
        this.sendIntervalMs = sendIntervalMs;
    }

    public long getSendJitterMs() {
        return sendJitterMs;
    }

    public void setSendJitterMs(long sendJitterMs) {
        this.sendJitterMs = sendJitterMs;
    }

    public long getStartupSpreadMs() {
        return startupSpreadMs;
    }

    public void setStartupSpreadMs(long startupSpreadMs) {
        this.startupSpreadMs = startupSpreadMs;
    }

    public long getReconnectDelayMs() {
        return reconnectDelayMs;
    }

    public void setReconnectDelayMs(long reconnectDelayMs) {
        this.reconnectDelayMs = reconnectDelayMs;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getReportIntervalSeconds() {
        return reportIntervalSeconds;
    }

    public void setReportIntervalSeconds(int reportIntervalSeconds) {
        this.reportIntervalSeconds = reportIntervalSeconds;
    }
}
