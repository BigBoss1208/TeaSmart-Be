package vn.teasmart.backend.security;

import java.util.List;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.core.AuthenticationException;
import vn.teasmart.backend.service.UserService;

@Component
public class TeaSmartJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final UserService userService;

    public TeaSmartJwtAuthenticationConverter(UserService userService) {
        this.userService = userService;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            String subject = jwt.getSubject();
            if (subject == null || !subject.matches("[0-9]+")) {
                throw new InvalidBearerTokenException("Authentication failed.");
            }
            var user = userService.requireActiveUser(Long.valueOf(subject));
            return new JwtAuthenticationToken(jwt,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())), subject);
        } catch (NumberFormatException | AuthenticationException exception) {
            throw new InvalidBearerTokenException("Authentication failed.");
        }
    }
}
