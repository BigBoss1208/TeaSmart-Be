package vn.teasmart.backend.config;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.http.SessionCreationPolicy;
import vn.teasmart.backend.security.CustomUserDetailsService;
import vn.teasmart.backend.security.TeaSmartJwtAuthenticationConverter;
import vn.teasmart.backend.security.JsonAuthenticationEntryPoint;
import vn.teasmart.backend.security.JsonAccessDeniedHandler;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(CustomUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        var provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            TeaSmartJwtAuthenticationConverter converter, JsonAuthenticationEntryPoint entryPoint,
            JsonAccessDeniedHandler deniedHandler) throws Exception {
        // Authentication is exclusively an explicit Bearer header, with no cookie session.
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.GET,
                        "/api/products", "/api/products/**",
                        "/api/categories", "/api/categories/**",
                        "/api/tea-regions", "/api/tea-regions/**",
                        "/api/stores", "/api/stores/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/users/me").hasAnyRole("CUSTOMER", "ADMIN")
                .requestMatchers("/api/cart", "/api/cart/**").hasRole("CUSTOMER")
                .requestMatchers("/api/orders", "/api/orders/**").hasRole("CUSTOMER")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().denyAll())
                .build();
    }
}
