package vn.teasmart.backend.security;

import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import vn.teasmart.backend.repository.UserRepository;

@Service
public class CustomUserDetailsService implements UserDetailsService {
    private final UserRepository repository;

    public CustomUserDetailsService(UserRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        var user = repository.findByEmail(email.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new UsernameNotFoundException("Authentication failed."));
        boolean valid = "ACTIVE".equals(user.getStatus())
                && ("CUSTOMER".equals(user.getRole()) || "ADMIN".equals(user.getRole()));
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getEmail()).password(user.getPasswordHash())
                .authorities(valid ? "ROLE_" + user.getRole() : "ROLE_INVALID")
                .disabled(!valid).build();
    }
}
