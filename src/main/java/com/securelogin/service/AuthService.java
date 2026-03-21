package com.securelogin.service;

import com.securelogin.dto.request.*;
import com.securelogin.dto.response.*;
import com.securelogin.model.User;
import com.securelogin.repository.UserRepository;
import com.securelogin.security.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtils jwtUtils;

    @Transactional
    public MessageResponse register(RegisterRequest req) {
        if (userRepository.existsByUsername(req.getUsername()))
            throw new IllegalArgumentException("Username is already taken.");
        if (userRepository.existsByEmail(req.getEmail()))
            throw new IllegalArgumentException("Email is already registered.");

        User user = User.builder()
                .username(req.getUsername())
                .email(req.getEmail())
                .password(passwordEncoder.encode(req.getPassword()))  // BCrypt hash
                .fullName(req.getFullName())
                .build();

        userRepository.save(user);
        log.info("Registered new user: {}", user.getUsername());
        return new MessageResponse("User registered successfully.");
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(req.getUsername(), req.getPassword()));

        SecurityContextHolder.getContext().setAuthentication(auth);

        UserDetails userDetails = (UserDetails) auth.getPrincipal();
        String token = jwtUtils.generateToken(userDetails);

        // Update last-login timestamp
        userRepository.findByUsername(userDetails.getUsername()).ifPresent(user -> {
            user.setLastLogin(Instant.now());
            userRepository.save(user);
        });

        log.info("Login successful for user: {}", userDetails.getUsername());
        return new AuthResponse(token, jwtUtils.getExpirationMs());
    }
}
