const { createProxyMiddleware } = require("http-proxy-middleware");

module.exports = function (app) {
    // Proxy WebSocket connections (SockJS endpoint) with WS upgrade support
    app.use(
        "/ws",
        createProxyMiddleware({
            target: "http://localhost:9010",
            changeOrigin: true,
            ws: true,
        })
    );

    // Proxy all other API/backend HTTP requests
    app.use(
        ["/api", "/auth", "/login", "/logout", "/register"],
        createProxyMiddleware({
            target: "http://localhost:9010",
            changeOrigin: true,
        })
    );
};
