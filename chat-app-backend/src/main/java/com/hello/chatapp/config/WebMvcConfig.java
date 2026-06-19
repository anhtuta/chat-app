package com.hello.chatapp.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(@NonNull ViewControllerRegistry registry) {
        // Forward SPA routes to index.html so BrowserRouter works in production
        registry.addViewController("/login").setViewName("forward:/index.html");
        registry.addViewController("/register").setViewName("forward:/index.html");
        registry.addViewController("/group/{path:[^\\.]*}").setViewName("forward:/index.html");
        registry.addViewController("/group/{path:[^\\.]*}/{path2:[^\\.]*}").setViewName("forward:/index.html");
        registry.addViewController("/").setViewName("forward:/index.html");
    }
}
