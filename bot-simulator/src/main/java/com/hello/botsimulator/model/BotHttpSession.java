package com.hello.botsimulator.model;

import java.net.CookieManager;
import java.net.http.HttpClient;

public record BotHttpSession(HttpClient httpClient, CookieManager cookieManager) {

    public String cookieHeader() {
        return cookieManager.getCookieStore().getCookies().stream()
                .map(cookie -> cookie.getName() + "=" + cookie.getValue())
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
    }
}
