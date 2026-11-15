package vn.teasmart.backend.service;

import java.time.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.teasmart.backend.entity.User;
import vn.teasmart.backend.repository.UserRepository;
import vn.teasmart.backend.dto.response.AuthResponse;
import vn.teasmart.backend.exception.GoogleAuthException;
import vn.teasmart.backend.security.*;

@Service
public class GoogleAuthService {
    private static final Logger log = LoggerFactory.getLogger(GoogleAuthService.class);
    private final GoogleTokenVerifier verifier;
    private final GoogleLoginChallengeStore challenges;
    private final UserRepository users;
    private final JwtService jwt;
    private final UserService userService;
    private final TransactionTemplate transaction;
    private final vn.teasmart.backend.notification.OrderNotifications notifications;
    public GoogleAuthService(GoogleTokenVerifier verifier,GoogleLoginChallengeStore challenges,
            UserRepository users,JwtService jwt,UserService userService,PlatformTransactionManager manager, vn.teasmart.backend.notification.OrderNotifications notifications) {
        this.verifier=verifier;this.challenges=challenges;this.users=users;this.jwt=jwt;this.userService=userService;
        this.notifications=notifications;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public AuthResponse login(String idToken,String binding) {
        String nonce;
        try { nonce=challenges.require(binding); }
        catch (GoogleAuthException failure) { log.warn("Google login rejected at CHALLENGE_LOOKUP"); throw failure; }
        GoogleTokenVerifier.Identity identity;
        try { identity=verifier.verify(idToken,nonce); } // No DB transaction during remote verification.
        catch (GoogleAuthException failure) { log.warn("Google login rejected at TOKEN_VERIFICATION"); throw failure; }
        try { challenges.consume(binding,nonce); } // Exactly one concurrent request may proceed.
        catch (GoogleAuthException failure) { log.warn("Google login rejected at CHALLENGE_CONSUME"); throw failure; }
        try { return transaction.execute(status->resolve(identity,true)); }
        catch(DataIntegrityViolationException conflict) {
            // execute() has rolled back fully; use a new transaction/current snapshot.
            return transaction.execute(status->resolve(identity,false));
        }
    }
    private AuthResponse resolve(GoogleTokenVerifier.Identity identity,boolean create) {
        User user=users.findByGoogleSub(identity.sub()).orElse(null);
        if(user==null) {
            if(users.existsByEmail(identity.email()))throw GoogleAuthException.conflict();
            if(!create)throw GoogleAuthException.unavailable();
            user=new User();user.setFullName(identity.name());user.setEmail(identity.email());
            user.setGoogleSub(identity.sub());user.setPasswordHash(null);user.setPhone(null);
            user.setRole("CUSTOMER");user.setStatus("ACTIVE");
            LocalDateTime now=LocalDateTime.now(ZoneId.of("Asia/Ho_Chi_Minh")).withNano(0);
            user.setCreatedAt(now);user.setUpdatedAt(now);users.saveAndFlush(user);
            notifications.welcome(user);
        }
        if(!"CUSTOMER".equals(user.getRole())||!"ACTIVE".equals(user.getStatus())) {
            log.warn("Google login rejected at ACCOUNT_ELIGIBILITY");
            throw GoogleAuthException.failed();
        }
        var response = new AuthResponse(jwt.generateAccessToken(user),"Bearer",jwt.getExpirationSeconds(),userService.getCurrentUser(user.getUserId()));
        notifications.loggedIn(user, true);
        return response;
    }
}
