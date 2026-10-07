package vn.teasmart.backend.service;

import java.time.LocalDateTime;
import java.util.Locale;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.dto.request.RegisterRequest;
import vn.teasmart.backend.dto.response.UserResponse;
import vn.teasmart.backend.entity.User;
import vn.teasmart.backend.exception.DuplicateEmailException;
import vn.teasmart.backend.repository.UserRepository;
import vn.teasmart.backend.dto.request.LoginRequest;
import vn.teasmart.backend.dto.response.AuthResponse;
import vn.teasmart.backend.security.JwtService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserService userService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager, JwtService jwtService, UserService userService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userService = userService;
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        // BCrypt must never silently truncate a supplied password.
        if (request.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new BadCredentialsException("Authentication failed.");
        }
        authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email, request.password()));
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Authentication failed."));
        user = userService.requireActiveUser(user.getUserId());
        return new AuthResponse(jwtService.generateAccessToken(user), "Bearer",
                jwtService.getExpirationSeconds(), userService.getCurrentUser(user.getUserId()));
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException();
        }

        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setPhone(request.phone().trim());
        user.setRole("CUSTOMER");
        user.setStatus("ACTIVE");
        user.setAvatarUrl(null);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            if (isEmailUniqueViolation(exception)) {
                throw new DuplicateEmailException();
            }
            throw exception;
        }
        return new UserResponse(user.getUserId(), user.getFullName(), user.getEmail(),
                user.getPhone(), user.getAvatarUrl(), user.getRole(), user.getCreatedAt());
    }

    private boolean isEmailUniqueViolation(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                String constraint = violation.getConstraintName();
                if (constraint != null) {
                    String normalized = constraint.replace("`", "").replace("'", "");
                    if (violation.getErrorCode() == 1062
                            && (normalized.equals("uk_users_email")
                            || normalized.equals("users.uk_users_email"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
