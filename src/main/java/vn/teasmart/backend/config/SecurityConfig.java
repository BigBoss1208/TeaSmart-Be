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
                .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login",
                        "/api/auth/forgot-password", "/api/auth/reset-password", "/api/auth/reset-password/verify").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/google", "/api/auth/google/challenge").permitAll()
                .requestMatchers(HttpMethod.GET,
                        "/api/products", "/api/products/**", "/api/recommendations",
                        "/api/categories", "/api/categories/**",
                        "/api/tea-regions", "/api/tea-regions/**",
                        "/api/stores", "/api/stores/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/payments/vnpay/ipn").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/review-images/*").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/users/me").hasAnyRole("CUSTOMER", "ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/avatars/*").permitAll()
                .requestMatchers("/api/users/me/**").hasAnyRole("CUSTOMER", "ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/auth/google/link/callback").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/google/link", "/api/auth/google/link/start", "/api/auth/google/link/complete").hasRole("CUSTOMER")
                .requestMatchers(HttpMethod.POST, "/api/leaf-classifications/predict").authenticated()
                .requestMatchers("/api/chatbot/**", "/api/recommendations/preferences").hasRole("CUSTOMER")
                 .requestMatchers("/api/wishlist", "/api/wishlist/**").hasRole("CUSTOMER")
                .requestMatchers("/api/cart", "/api/cart/**").hasRole("CUSTOMER")
                .requestMatchers("/api/addresses", "/api/addresses/**").hasRole("CUSTOMER")
                .requestMatchers("/api/reviews", "/api/reviews/**").hasRole("CUSTOMER")
                .requestMatchers("/api/checkout-sessions/**").hasRole("CUSTOMER")
                .requestMatchers(HttpMethod.POST, "/api/payments/vnpay/return").hasRole("CUSTOMER")
                .requestMatchers("/api/orders", "/api/orders/**").hasRole("CUSTOMER")
                .requestMatchers("/api/seller-applications", "/api/seller-applications/me").hasRole("CUSTOMER")
                .requestMatchers("/api/seller/**").hasRole("SELLER")
                .requestMatchers(HttpMethod.GET, "/api/shop-logos/*").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().denyAll())
                .build();
    }
}
