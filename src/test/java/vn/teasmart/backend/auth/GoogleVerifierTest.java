package vn.teasmart.backend.auth;

import java.security.*;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.google.api.client.googleapis.auth.oauth2.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.Test;
import vn.teasmart.backend.security.GoogleIdTokenVerifierAdapter;
import vn.teasmart.backend.exception.GoogleAuthException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleVerifierTest {
    private KeyPair key() throws Exception { var g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);return g.generateKeyPair(); }
    private String token(KeyPair key,String audience,String issuer,int expiry,boolean verified,String nonce) throws Exception {
        var claims=new JWTClaimsSet.Builder().subject("fixture-sub").issuer(issuer).audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(5))).expirationTime(Date.from(Instant.now().plusSeconds(expiry)))
                .claim("email","fixture@example.invalid").claim("email_verified",verified).claim("nonce",nonce).claim("name","Fixture").build();
        var token=new SignedJWT(new JWSHeader(JWSAlgorithm.RS256),claims);
        token.sign(new RSASSASigner((RSAPrivateKey)key.getPrivate()));return token.serialize();
    }
    private GoogleIdTokenVerifierAdapter adapter(GooglePublicKeysManager manager) {
        return new GoogleIdTokenVerifierAdapter(new GoogleIdTokenVerifier.Builder(manager)
                .setAudience(List.of("fixture-client")).setAcceptableTimeSkewSeconds(0).build());
    }
    @Test void validatesSignatureAudienceIssuerExpiryEmailAndNonceOffline() throws Exception {
        var key=key();var manager=mock(GooglePublicKeysManager.class);
        when(manager.getPublicKeys()).thenReturn(List.of(key.getPublic()));var adapter=adapter(manager);
        try {
            var identity=adapter.verify(token(key,"fixture-client","https://accounts.google.com",300,true,"fixture-nonce"),"fixture-nonce");
            assertEquals("fixture-sub",identity.sub());
            for(String invalid:List.of(token(key(),"fixture-client","https://accounts.google.com",300,true,"fixture-nonce"),
                    token(key,"wrong-client","https://accounts.google.com",300,true,"fixture-nonce"),
                    token(key,"fixture-client","https://invalid.example",300,true,"fixture-nonce"),
                    token(key,"fixture-client","https://accounts.google.com",-5,true,"fixture-nonce"),
                    token(key,"fixture-client","https://accounts.google.com",300,false,"fixture-nonce"),
                    token(key,"fixture-client","https://accounts.google.com",300,true,"wrong"),
                    token(key,"fixture-client","https://accounts.google.com",300,true,null),"malformed")) {
                var error=assertThrows(GoogleAuthException.class,()->adapter.verify(invalid,"fixture-nonce"));
                assertEquals("GOOGLE_AUTH_FAILED",error.getCode());
            }
        } finally { adapter.close(); }
    }
    @Test void verifierOfflineIs503WithoutLeakingDetails() throws Exception {
        var manager=mock(GooglePublicKeysManager.class);when(manager.getPublicKeys()).thenThrow(new java.io.IOException("private diagnostic"));
        var adapter=adapter(manager);var encoded=token(key(),"fixture-client","https://accounts.google.com",300,true,"fixture-nonce");
        try { var error=assertThrows(GoogleAuthException.class,()->adapter.verify(encoded,"fixture-nonce"));
            assertEquals(503,error.getStatus());assertFalse(error.getMessage().contains("private diagnostic"));
        } finally { adapter.close(); }
    }
    @Test void timeoutDoesNotBlockLoginIndefinitely() throws Exception {
        var nativeVerifier=mock(GoogleIdTokenVerifier.class);
        when(nativeVerifier.verify(any(GoogleIdToken.class))).thenAnswer(call->{Thread.sleep(20000);return true;});
        var adapter=new GoogleIdTokenVerifierAdapter(nativeVerifier);
        String encoded=token(key(),"fixture-client","https://accounts.google.com",300,true,"fixture-nonce");
        try {long start=System.nanoTime();
            var error=assertThrows(GoogleAuthException.class,()->adapter.verify(encoded,"fixture-nonce"));
            assertEquals(503,error.getStatus());assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(9));
        }finally{adapter.close();}
    }
    @Test void diagnosticsReportOnlyVerifiedFailureCodes() throws Exception {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(GoogleIdTokenVerifierAdapter.class);
        var logs=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();logger.addAppender(logs);
        var key=key();var manager=mock(GooglePublicKeysManager.class);
        when(manager.getPublicKeys()).thenReturn(List.of(key.getPublic()));var adapter=adapter(manager);
        try {
            String[] reasons={"AUDIENCE_INVALID","ISSUER_INVALID","TOKEN_EXPIRED","NONCE_MISMATCH"};
            String[] inputs={token(key,"wrong-client","https://accounts.google.com",300,true,"fixture-nonce"),
                token(key,"fixture-client","https://invalid.example",300,true,"fixture-nonce"),
                token(key,"fixture-client","https://accounts.google.com",-5,true,"fixture-nonce"),
                token(key,"fixture-client","https://accounts.google.com",300,true,"wrong-nonce")};
            for(int i=0;i<inputs.length;i++) {
                logs.list.clear();final String input=inputs[i];
                assertThrows(GoogleAuthException.class,()->adapter.verify(input,"fixture-nonce"));
                assertEquals("Google token verification rejected: "+reasons[i],logs.list.get(logs.list.size()-1).getFormattedMessage());
            }
        } finally {adapter.close();logger.detachAppender(logs);logs.stop();}
    }
    @Test void boundedClockSkewAcceptsSmallDriftButRejectsLargeDrift() throws Exception {
        var key=key();var manager=mock(GooglePublicKeysManager.class);
        when(manager.getPublicKeys()).thenReturn(List.of(key.getPublic()));
        long now=System.currentTimeMillis();
        var tolerant=new GoogleIdTokenVerifierAdapter(new GoogleIdTokenVerifier.Builder(manager)
            .setAudience(List.of("fixture-client")).setAcceptableTimeSkewSeconds(60)
            .setClock(()->now-10000).build());
        var excessive=new GoogleIdTokenVerifierAdapter(new GoogleIdTokenVerifier.Builder(manager)
            .setAudience(List.of("fixture-client")).setAcceptableTimeSkewSeconds(60)
            .setClock(()->now-120000).build());
        try {
            String encoded=token(key,"fixture-client","https://accounts.google.com",300,true,"fixture-nonce");
            assertEquals("fixture-sub",tolerant.verify(encoded,"fixture-nonce").sub());
            assertThrows(GoogleAuthException.class,()->excessive.verify(encoded,"fixture-nonce"));
            assertThrows(GoogleAuthException.class,()->tolerant.verify(encoded,"wrong-nonce"));
        } finally {tolerant.close();excessive.close();}
    }
}