package vn.teasmart.backend.security;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.*;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vn.teasmart.backend.config.GoogleAuthConfig;
import vn.teasmart.backend.exception.GoogleAuthException;
import vn.teasmart.backend.util.LoginIdentifier;

@Component
public class GoogleIdTokenVerifierAdapter implements GoogleTokenVerifier {
    private static final Logger log = LoggerFactory.getLogger(GoogleIdTokenVerifierAdapter.class);
    private final GoogleIdTokenVerifier verifier;
    // Bound both waiting time and concurrency when Google is unreachable.
    private final ThreadPoolExecutor executor=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4),r->{Thread t=new Thread(r,"google-token-verifier");t.setDaemon(true);return t;},
            new ThreadPoolExecutor.AbortPolicy());
    @Autowired
    public GoogleIdTokenVerifierAdapter(GoogleAuthConfig config) {
        this(new GoogleIdTokenVerifier.Builder(new NetHttpTransport(),GsonFactory.getDefaultInstance())
                .setAudience(List.of(config.clientId())).setAcceptableTimeSkewSeconds(60).build());
    }
    public GoogleIdTokenVerifierAdapter(GoogleIdTokenVerifier verifier) { this.verifier=verifier; }
    @Override public Identity verify(String encoded,String expectedNonce) {
        if(encoded==null||encoded.length()>16384||expectedNonce==null)throw rejected("OTHER_VERIFICATION_FAILURE");
        GoogleIdToken token;
        try { token=GoogleIdToken.parse(GsonFactory.getDefaultInstance(),encoded); }
        catch(IOException|IllegalArgumentException e) { throw rejected("OTHER_VERIFICATION_FAILURE"); }
        Future<Boolean> task;
        try { task=executor.submit(()->verifier.verify(token)); }
        catch(RejectedExecutionException e) { throw unavailable(); }
        try { if(!task.get(6,TimeUnit.SECONDS))throw rejected(rejectionReason(token)); }
        catch(TimeoutException e) { task.cancel(true);throw unavailable(); }
        catch(InterruptedException e) { task.cancel(true);Thread.currentThread().interrupt();throw unavailable(); }
        catch(ExecutionException e) {
            if(e.getCause() instanceof GeneralSecurityException)throw rejected("OTHER_VERIFICATION_FAILURE");
            throw unavailable();
        }
        var p=token.getPayload();
        if(!expectedNonce.equals(p.get("nonce")))throw rejected("NONCE_MISMATCH");
        String sub=p.getSubject(),email=p.getEmail();
        if(!Boolean.TRUE.equals(p.getEmailVerified())||sub==null||sub.isBlank()||sub.length()>255
                ||email==null||!LoginIdentifier.isEmail(email)||!LoginIdentifier.isValid(email)
                )throw rejected("OTHER_VERIFICATION_FAILURE");
        String name=p.get("name") instanceof String value?value.trim():"";
        if(name.isEmpty())name="Khách hàng TeaSmart";
        if(name.length()>100)name=name.substring(0,100);
        return new Identity(sub,LoginIdentifier.normalize(email),name);
    }
    // Failed checks are diagnostic only; no claims are logged or accepted without verification.
    private String rejectionReason(GoogleIdToken token) {
        try {
            if(verifier.getIssuers()!=null&&!token.verifyIssuer(verifier.getIssuers()))return "ISSUER_INVALID";
            if(verifier.getAudience()!=null&&!token.verifyAudience(verifier.getAudience()))return "AUDIENCE_INVALID";
            if(!token.verifyExpirationTime(verifier.getClock().currentTimeMillis(),verifier.getAcceptableTimeSkewSeconds()))return "TOKEN_EXPIRED";
            if(!token.verifyIssuedAtTime(verifier.getClock().currentTimeMillis(),verifier.getAcceptableTimeSkewSeconds()))return "TOKEN_ISSUED_IN_FUTURE";
        } catch(RuntimeException ignored) { }
        // False alone does not prove signature failure.
        return "OTHER_VERIFICATION_FAILURE";
    }
    private GoogleAuthException rejected(String reason) {
        log.warn("Google token verification rejected: {}",reason);
        return GoogleAuthException.failed();
    }
    private GoogleAuthException unavailable() {
        log.warn("Google token verification rejected: VERIFIER_UNAVAILABLE");
        return GoogleAuthException.unavailable();
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
