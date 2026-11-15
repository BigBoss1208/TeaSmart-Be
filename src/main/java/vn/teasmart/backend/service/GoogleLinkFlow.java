package vn.teasmart.backend.service;

import java.security.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.security.oauth2.jwt.Jwt;
import vn.teasmart.backend.config.GoogleAuthConfig;
import vn.teasmart.backend.security.GoogleTokenVerifier;
import vn.teasmart.backend.exception.*;
import vn.teasmart.backend.dto.response.AuthResponse;

/** LINK_GOOGLE-only, bounded single-instance flow. LOGIN challenges cannot complete it. */
@Service
public class GoogleLinkFlow {
    public record Started(String authorizationUrl,int expiresIn){}
    public record Launch(String binding,Started response){}
    private static class Pending {
        long owner;String fingerprint,state,nonce,verifier,origin;Instant expires;boolean claimed;GoogleTokenVerifier.Identity identity;
    }
    private final Map<String,Pending> pending=new HashMap<>();
    private final GoogleAuthConfig config;private final GoogleCodeClient client;private final GoogleTokenVerifier verifier;private final GoogleLinkService links;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public GoogleLinkFlow(GoogleAuthConfig config,GoogleCodeClient client,GoogleTokenVerifier verifier,GoogleLinkService links){this(config,client,verifier,links,Clock.systemUTC());}
    GoogleLinkFlow(GoogleAuthConfig config,GoogleCodeClient client,GoogleTokenVerifier verifier,GoogleLinkService links,Clock clock){this.config=config;this.client=client;this.verifier=verifier;this.links=links;this.clock=clock;}
    private String random(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    static String hash(String value){try{return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException();}}
    private void cleanup(){pending.values().removeIf(p->!p.expires.isAfter(clock.instant()));}
    public synchronized Launch start(Jwt jwt,String origin,String previous){
        config.requireOrigin(origin);client.available();
        var now=clock.instant();if(jwt.getIssuedAt()==null||jwt.getIssuedAt().isBefore(now.minusSeconds(600))||jwt.getIssuedAt().isAfter(now.plusSeconds(60)))throw new ProfileException(409,"GOOGLE_REAUTH_REQUIRED","Vui lòng đăng nhập lại TeaSmart để xác minh danh tính trước khi liên kết Google.");
        if(!links.status(Long.valueOf(jwt.getSubject())).canLink())throw new ProfileException(409,"GOOGLE_ALREADY_LINKED","Tài khoản không thể thêm liên kết Google.");
        cleanup();if(previous!=null)pending.remove(previous);if(pending.size()>=1000)throw GoogleAuthException.unavailable();
        var p=new Pending();p.owner=Long.parseLong(jwt.getSubject());p.fingerprint=hash(jwt.getTokenValue());p.state=random();p.nonce=random();p.verifier=random();p.origin=origin;p.expires=now.plusSeconds(300);
        String url=client.authorization(p.state,p.nonce,hash(p.verifier));String binding=random();pending.put(binding,p);return new Launch(binding,new Started(url,300));
    }
    private synchronized Pending require(String binding){cleanup();var p=pending.get(binding);if(p==null)throw GoogleAuthException.failed();return p;}
    public String callback(String binding,String state,String code,String error){
        Pending p;
        synchronized(this){p=require(binding);if(state==null||!MessageDigest.isEqual(p.state.getBytes(StandardCharsets.UTF_8),state.getBytes(StandardCharsets.UTF_8))||p.claimed)throw GoogleAuthException.failed();p.claimed=true;if(error!=null){pending.remove(binding);return p.origin+"/account?google_link=cancelled";}}
        try{var identity=verifier.verify(client.exchange(code,p.verifier),p.nonce);synchronized(this){if(require(binding)!=p)throw GoogleAuthException.failed();p.identity=identity;}return p.origin+"/account?google_link=ready";}
        catch(RuntimeException e){synchronized(this){pending.remove(binding);}throw e;}
    }
    public AuthResponse complete(Jwt jwt,String binding,String origin){
        Pending p;synchronized(this){config.requireOrigin(origin);p=require(binding);if(p.owner!=Long.parseLong(jwt.getSubject())||!p.fingerprint.equals(hash(jwt.getTokenValue()))||!p.origin.equals(origin)||p.identity==null)throw GoogleAuthException.failed();pending.remove(binding);}
        return links.attachVerified(p.owner,p.identity);
    }
}