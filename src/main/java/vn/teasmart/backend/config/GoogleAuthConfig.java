package vn.teasmart.backend.config;

import java.net.URI;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.teasmart.backend.exception.GoogleAuthException;

@Component
public class GoogleAuthConfig {
    private final boolean enabled;
    private final String clientId;
    private final Set<String> origins;
    public GoogleAuthConfig(@Value("${teasmart.google.enabled:false}") boolean enabled,
            @Value("${teasmart.google.client-id:}") String clientId,
            @Value("${teasmart.google.allowed-origins:}") String allowedOrigins) {
        this.enabled=enabled;this.clientId=clientId.trim();
        Set<String> validated=new HashSet<>();
        for(String value:allowedOrigins.split(",")) {
            String origin=value.trim();if(origin.isEmpty())continue;
            URI uri=URI.create(origin);
            boolean local=uri.getHost()!=null&&Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost());
            if(uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                    || (uri.getRawPath()!=null&&!uri.getRawPath().isEmpty())
                    || !("https".equals(uri.getScheme()) || (local&&"http".equals(uri.getScheme()))))
                throw new IllegalArgumentException("Invalid Google origin configuration.");
            validated.add(origin);
        }
        origins=Set.copyOf(validated);
        if(enabled&&(this.clientId.isEmpty()||origins.isEmpty()))
            throw new IllegalArgumentException("Enabled Google authentication requires client ID and allowed origins.");
    }
    public String clientId() { return clientId; }
    public void requireOrigin(String origin) {
        if(!enabled)throw GoogleAuthException.unavailable();
        if(origin==null||!origins.contains(origin))throw GoogleAuthException.failed();
    }
}
