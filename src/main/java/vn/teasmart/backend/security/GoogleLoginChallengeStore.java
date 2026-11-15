package vn.teasmart.backend.security;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import vn.teasmart.backend.exception.GoogleAuthException;

/** Bounded single-instance store; all lookup/consume operations are atomic. */
@Component
public class GoogleLoginChallengeStore {
    public record Issued(String binding, String nonce, int expiresIn) { }
    private record Challenge(String nonce, Instant expiresAt) { }
    private final Map<String,Challenge> challenges=new HashMap<>();
    private final SecureRandom random=new SecureRandom();
    private final Clock clock;
    private final int capacity;
    public GoogleLoginChallengeStore() { this(Clock.systemUTC(),1000); }
    public GoogleLoginChallengeStore(Clock clock,int capacity) { this.clock=clock;this.capacity=capacity; }
    private String randomValue() {
        byte[] bytes=new byte[32];random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private void cleanup() { challenges.values().removeIf(c->!c.expiresAt().isAfter(clock.instant())); }
    public synchronized Issued issue(String previousBinding) {
        cleanup();if(previousBinding!=null)challenges.remove(previousBinding);
        if(challenges.size()>=capacity)throw GoogleAuthException.unavailable();
        String binding=randomValue(),nonce=randomValue();
        challenges.put(binding,new Challenge(nonce,clock.instant().plusSeconds(300)));
        return new Issued(binding,nonce,300);
    }
    public synchronized String require(String binding) {
        cleanup();Challenge c=binding==null?null:challenges.get(binding);
        if(c==null)throw GoogleAuthException.failed();return c.nonce();
    }
    public synchronized void consume(String binding,String expectedNonce) {
        String nonce=require(binding);
        if(!nonce.equals(expectedNonce))throw GoogleAuthException.failed();
        challenges.remove(binding);
    }
}
