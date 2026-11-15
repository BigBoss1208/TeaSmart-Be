package vn.teasmart.backend.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.teasmart.backend.dto.request.RegisterRequest;
import vn.teasmart.backend.dto.response.UserResponse;
import vn.teasmart.backend.service.AuthService;
import vn.teasmart.backend.dto.request.LoginRequest;
import vn.teasmart.backend.dto.response.AuthResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import vn.teasmart.backend.config.GoogleAuthConfig;
import vn.teasmart.backend.security.GoogleLoginChallengeStore;
import vn.teasmart.backend.service.GoogleAuthService;
import vn.teasmart.backend.dto.request.GoogleLoginRequest;
import vn.teasmart.backend.dto.response.GoogleChallengeResponse;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final GoogleAuthConfig googleConfig;
    private final GoogleAuthService googleAuth;
    private final GoogleLoginChallengeStore challenges;
    private static final String COOKIE="teasmart_google_challenge";

    public AuthController(AuthService authService,GoogleAuthConfig googleConfig,
            GoogleAuthService googleAuth,GoogleLoginChallengeStore challenges) {
        this.authService = authService;
        this.googleConfig=googleConfig;this.googleAuth=googleAuth;this.challenges=challenges;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    private String binding(HttpServletRequest request) {
        if(request.getCookies()!=null)for(var cookie:request.getCookies())
            if(COOKIE.equals(cookie.getName()))return cookie.getValue();
        return null;
    }
    private ResponseCookie cookie(HttpServletRequest request,String value,long age) {
        boolean secure=request.isSecure()||request.getHeader("Origin").startsWith("https://");
        return ResponseCookie.from(COOKIE,value).httpOnly(true).secure(secure)
                .sameSite("Strict").path("/api/auth/google").maxAge(age).build();
    }
    @PostMapping("/google/challenge")
    public ResponseEntity<GoogleChallengeResponse> googleChallenge(HttpServletRequest request) {
        googleConfig.requireOrigin(request.getHeader("Origin"));
        var challenge=challenges.issue(binding(request));
        return ResponseEntity.ok().header("Cache-Control","no-store")
                .header("Set-Cookie",cookie(request,challenge.binding(),challenge.expiresIn()).toString())
                .body(new GoogleChallengeResponse(challenge.nonce(),challenge.expiresIn()));
    }
    @PostMapping(value="/google",consumes="application/json")
    public ResponseEntity<AuthResponse> googleLogin(@Valid @RequestBody GoogleLoginRequest body,HttpServletRequest request) {
        googleConfig.requireOrigin(request.getHeader("Origin"));
        var response=googleAuth.login(body.idToken(),binding(request));
        return ResponseEntity.ok().header("Cache-Control","no-store")
                .header("Set-Cookie",cookie(request,"",0).toString()).body(response);
    }
}
