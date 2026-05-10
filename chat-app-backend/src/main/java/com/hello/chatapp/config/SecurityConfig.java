package com.hello.chatapp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers("/api/**", "/ws/**") // Disable CSRF for API and WebSocket
                )
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints
                        .requestMatchers("/api/auth/**").permitAll()
                        // SPA entry points and static assets
                        .requestMatchers(
                                "/", "/index.html", "/manifest.json", "/asset-manifest.json", "/favicon.ico",
                                "/*.js", "/*.css", "/*.json", "/*.ico", "/*.png", "/*.svg", "/*.jpg",
                                "/*.jpeg", "/*.gif", "/*.woff", "/*.woff2", "/*.ttf", "/*.eot",
                                "/static/**", "/login", "/register", "/group/**")
                        .permitAll()
                        // Protected APIs and WebSocket endpoints
                        .requestMatchers("/api/**").authenticated()
                        .requestMatchers("/ws/**").authenticated()
                        .anyRequest().permitAll())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                // Login is handled by the SPA calling /api/auth/login
                .formLogin(form -> form.disable())
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessUrl("/login")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll());

        return http.build();
    }
}

