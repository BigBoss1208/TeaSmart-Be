package vn.teasmart.backend.auth;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import vn.teasmart.backend.repository.UserRepository;
import vn.teasmart.backend.service.PasswordResetService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class PasswordResetSafetyTest {
    @Test void disabledFeatureNeverTouchesMissingMigrationOrMail() {
        var users=mock(UserRepository.class);var jdbc=mock(JdbcTemplate.class);var encoder=mock(PasswordEncoder.class);var events=mock(ApplicationEventPublisher.class);
        var service=new PasswordResetService(users,jdbc,encoder,events,false,"http://localhost:3000",false);
        service.request("test@example.invalid");assertFalse(service.verify("a".repeat(43)));assertFalse(service.reset("a".repeat(43),"Password123!"));
        verifyNoInteractions(users,jdbc,encoder,events);
    }
    @Test void unsafeFrontendOrDisabledMailCannotEnableReset() {
        for(String url:new String[]{"http://public.example","https://user@public.example","https://public.example?token=bad","https://public.example#bad"})
            assertThrows(IllegalArgumentException.class,()->new PasswordResetService(null,null,null,null,true,url,true));
        assertThrows(IllegalArgumentException.class,()->new PasswordResetService(null,null,null,null,true,"http://localhost:3000",false));
    }
}
