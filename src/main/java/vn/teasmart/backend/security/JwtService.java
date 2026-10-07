package vn.teasmart.backend.security;

import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import vn.teasmart.backend.entity.User;

@Service
public class JwtService {
    private final JwtEncoder encoder;
    private final String issuer;
    private final long expirationSeconds;

    public JwtService(JwtEncoder encoder, @Value("${teasmart.jwt.issuer}") String issuer,
            @Value("${teasmart.jwt.expiration-seconds}") long expirationSeconds) {
        if (expirationSeconds <= 0 || issuer.isBlank()) {
            throw new IllegalArgumentException("Invalid JWT issuer or expiration configuration.");
        }
        this.encoder = encoder;
        this.issuer = issuer;
        this.expirationSeconds = expirationSeconds;
    }

    public String generateAccessToken(User user) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().subject(user.getUserId().toString())
                .issuer(issuer).issuedAt(now).expiresAt(now.plusSeconds(expirationSeconds)).build();
        return encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    public long getExpirationSeconds() {
        return expirationSeconds;
    }
}
