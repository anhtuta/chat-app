package com.hello.chatapp.service;

import com.hello.chatapp.entity.User;
import com.hello.chatapp.exception.BadRequestException;
import com.hello.chatapp.exception.UnauthorizedException;
import com.hello.chatapp.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("null")
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    @Test
    void register_savesNewUserWithEncodedPassword() {
        // Arrange
        User savedUser = new User("alice", "encoded-secret");
        savedUser.setId(1L);

        // Stub
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(passwordEncoder.encode("secret")).thenReturn("encoded-secret");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        // Act
        User result = authService.register("alice", "secret");

        // Assert
        assertThat(result).isSameAs(savedUser);

        // Creates a captor typed for User. Mockito will store whatever object was passed to save(...) so you can read it later.
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);

        // Verify and Capture: Intercept the object passed to the mock (the save() method).
        // verify(): asserts save was called exactly once (default Mockito verify behavior).
        // capture(): registers the captor as the argument matcher and records the User instance that was passed in.
        verify(userRepository).save(userCaptor.capture());

        // Reads the captured User and asserts its username and password.
        assertThat(userCaptor.getValue().getUsername()).isEqualTo("alice");
        assertThat(userCaptor.getValue().getPassword()).isEqualTo("encoded-secret");

        // Verify that the passwordEncoder.encode("secret") method was called exactly once.
        verify(passwordEncoder).encode("secret");
    }

    @Test
    void register_throwsWhenUsernameAlreadyExists() {
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> authService.register("alice", "secret"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Username already exists");

        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void login_returnsUserWhenCredentialsAreValid() {
        User user = new User("alice", "encoded-secret");
        user.setId(1L);

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret", "encoded-secret")).thenReturn(true);

        User result = authService.login("alice", "secret");

        assertThat(result).isSameAs(user);
        verify(passwordEncoder).matches("secret", "encoded-secret");
    }

    @Test
    void login_throwsWhenUsernameNotFound() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("alice", "secret"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid username or password");

        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void login_throwsWhenPasswordDoesNotMatch() {
        User user = new User("alice", "encoded-secret");

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encoded-secret")).thenReturn(false);

        assertThatThrownBy(() -> authService.login("alice", "wrong"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid username or password");

        verify(passwordEncoder).matches(eq("wrong"), eq("encoded-secret"));
    }
}
