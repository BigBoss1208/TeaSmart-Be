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
import vn.teasmart.backend.exception.DuplicatePhoneException;
import vn.teasmart.backend.repository.UserRepository;
import vn.teasmart.backend.dto.request.LoginRequest;
import vn.teasmart.backend.dto.response.AuthResponse;
import vn.teasmart.backend.security.JwtService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import vn.teasmart.backend.util.LoginIdentifier;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final UserService userService;
    private final vn.teasmart.backend.notification.OrderNotifications notifications;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            AuthenticationManager authenticationManager, JwtService jwtService, UserService userService, vn.teasmart.backend.notification.OrderNotifications notifications) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userService = userService;
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String identifier = LoginIdentifier.normalize(request.email());
        // BCrypt must never silently truncate a supplied password.
        if (request.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new BadCredentialsException("Authentication failed.");
        }
        var authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(identifier, request.password()));
        User user = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new BadCredentialsException("Authentication failed."));
        user = userService.requireActiveUser(user.getUserId());
        var response = new AuthResponse(jwtService.generateAccessToken(user), "Bearer",
                jwtService.getExpirationSeconds(), userService.getCurrentUser(user.getUserId()));
        notifications.loggedIn(user, false);
        return response;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException();
        }
        if (userRepository.existsByPhone(request.phone())) {
            throw new DuplicatePhoneException();
        }

        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.setFullName(request.fullName().trim());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setPhone(request.phone());
        user.setRole("CUSTOMER");
        user.setStatus("ACTIVE");
        user.setAvatarUrl(null);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            if (isUniqueViolation(exception, "uk_users_email")) {
                throw new DuplicateEmailException();
            }
            if (isUniqueViolation(exception, "uk_users_phone")) {
                throw new DuplicatePhoneException();
            }
            throw exception;
        }
        notifications.welcome(user);
        return userService.getCurrentUser(user.getUserId());
    }

    private boolean isUniqueViolation(Throwable exception, String expectedConstraint) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                String constraint = violation.getConstraintName();
                if (constraint != null) {
                    String normalized = constraint.replace("`", "").replace("'", "");
                    if (violation.getErrorCode() == 1062
                            && (normalized.equals(expectedConstraint)
                            || normalized.equals("users." + expectedConstraint))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
