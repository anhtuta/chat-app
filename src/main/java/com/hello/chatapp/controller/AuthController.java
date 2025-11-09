package com.hello.chatapp.controller;

import com.hello.chatapp.dto.AuthCheckResponse;
import com.hello.chatapp.dto.AuthResponse;
import com.hello.chatapp.dto.UserRequest;
import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.BadRequestException;
import com.hello.chatapp.service.AuthService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    @Autowired
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody UserRequest request) {
        // Validate input
        validateUserRequest(request);

        User user = authService.register(request.getUsername(), request.getPassword());

        return ResponseEntity.ok(AuthResponse.builder()
                .success(true)
                .message("Registration successful")
                .username(user.getUsername())
                .build());
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody UserRequest request, HttpSession session) {
        // Validate input
        validateUserRequest(request);

        User user = authService.login(request.getUsername(), request.getPassword());

        // Authenticate user with Spring Security
        authenticateUser(user, session);

        return ResponseEntity.ok(AuthResponse.builder()
                .success(true)
                .message("Login successful")
                .username(user.getUsername())
                .build());
    }

    @PostMapping("/logout")
    public ResponseEntity<AuthResponse> logout(HttpSession session) {
        // Clear SecurityContext
        SecurityContextHolder.clearContext();

        // Invalidate session
        session.invalidate();

        return ResponseEntity.ok(AuthResponse.builder()
                .success(true)
                .message("Logout successful")
                .build());
    }

    @GetMapping("/check")
    public ResponseEntity<AuthCheckResponse> checkAuth() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.isAuthenticated()
                && !authentication.getName().equals("anonymousUser")) {
            return ResponseEntity.ok(AuthCheckResponse.builder()
                    .authenticated(true)
                    .username(authentication.getName())
                    .build());
        } else {
            return ResponseEntity.ok(AuthCheckResponse.builder()
                    .authenticated(false)
                    .build());
        }
    }

    /**
     * Validates UserRequest input.
     * Throws BadRequestException if validation fails.
     */
    private void validateUserRequest(UserRequest request) {
        if (request.getUsername() == null || request.getUsername().trim().isEmpty()) {
            throw new BadRequestException("Username is required");
        }

        if (request.getPassword() == null || request.getPassword().trim().isEmpty()) {
            throw new BadRequestException("Password is required");
        }
    }

    private void authenticateUser(User user, HttpSession session) {
        // Create authentication token
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                user.getUsername(),
                null,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

        // Set authentication in SecurityContext
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);

        // Store SecurityContext in session
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);

        // Store user object in session
        session.setAttribute("user", user);
    }
}
