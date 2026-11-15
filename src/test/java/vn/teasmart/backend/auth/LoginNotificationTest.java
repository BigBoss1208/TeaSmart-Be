package vn.teasmart.backend.auth;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import vn.teasmart.backend.dto.request.LoginRequest;
import vn.teasmart.backend.entity.User;
import vn.teasmart.backend.notification.OrderNotifications;
import vn.teasmart.backend.repository.UserRepository;
import vn.teasmart.backend.security.JwtService;
import vn.teasmart.backend.service.AuthService;
import vn.teasmart.backend.service.UserService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoginNotificationTest {
    final UserRepository users = mock(UserRepository.class);
    final AuthenticationManager manager = mock(AuthenticationManager.class);
    final JwtService jwt = mock(JwtService.class);
    final UserService profiles = mock(UserService.class);
    final OrderNotifications notifications = mock(OrderNotifications.class);
    final AuthService service = new AuthService(users, mock(PasswordEncoder.class), manager, jwt, profiles, notifications);
    User account;

    @BeforeEach void setup() {
        account = new User(); account.setUserId(19L); account.setEmail("account@example.invalid");
        account.setRole("CUSTOMER"); account.setStatus("ACTIVE");
        when(manager.authenticate(any())).thenReturn(
                UsernamePasswordAuthenticationToken.authenticated(account.getEmail(), null, java.util.List.of()));
        when(users.findByEmail(account.getEmail())).thenReturn(Optional.of(account));
        when(profiles.requireActiveUser(account.getUserId())).thenReturn(account);
        when(jwt.generateAccessToken(account)).thenReturn("fixture-access-token");
    }
    @ParameterizedTest @ValueSource(strings = {" ACCOUNT@example.invalid ", "0912345678", "+84 912 345 678"})
    void successfulEmailAndPhoneLoginNotifyCanonicalAccount(String identifier) {
        var response = service.login(new LoginRequest(identifier, "fixture-password"));
        assertEquals("fixture-access-token", response.accessToken());
        verify(notifications).loggedIn(account, false);
        verify(notifications, never()).welcome(any());
    }
    @Test void eachSuccessfulLoginIncludingAdminNotifiesOnce() {
        account.setRole("ADMIN");
        service.login(new LoginRequest(account.getEmail(), "fixture-password"));
        service.login(new LoginRequest(account.getEmail(), "fixture-password"));
        verify(notifications, times(2)).loggedIn(account, false);
    }
    @Test void wrongPasswordOrUnknownAccountDoesNotNotify() {
        when(manager.authenticate(any())).thenThrow(new BadCredentialsException("Authentication failed."));
        assertThrows(BadCredentialsException.class, () -> service.login(new LoginRequest(account.getEmail(), "wrong")));
        verifyNoInteractions(notifications);
    }
    @Test void inactiveAccountDoesNotNotify() {
        when(profiles.requireActiveUser(account.getUserId())).thenThrow(new BadCredentialsException("Authentication failed."));
        assertThrows(BadCredentialsException.class, () -> service.login(new LoginRequest(account.getEmail(), "fixture-password")));
        verifyNoInteractions(notifications);
    }
    @Test void failedResponseCreationDoesNotNotify() {
        when(jwt.generateAccessToken(account)).thenThrow(new IllegalStateException("Controlled failure"));
        assertThrows(IllegalStateException.class, () -> service.login(new LoginRequest(account.getEmail(), "fixture-password")));
        verifyNoInteractions(notifications);
    }
}
