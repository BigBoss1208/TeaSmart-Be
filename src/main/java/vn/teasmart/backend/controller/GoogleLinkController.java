package vn.teasmart.backend.controller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import vn.teasmart.backend.service.GoogleLinkService;
import vn.teasmart.backend.config.GoogleAuthConfig;
import vn.teasmart.backend.dto.response.AuthResponse;
@RestController
public class GoogleLinkController {
 private final GoogleLinkService service;private final GoogleAuthConfig config;
 public GoogleLinkController(GoogleLinkService service,GoogleAuthConfig config){this.service=service;this.config=config;}
 @GetMapping("/api/users/me/google-link") public GoogleLinkService.LinkStatus status(@AuthenticationPrincipal Jwt jwt){return service.status(Long.valueOf(jwt.getSubject()));}
 public record LinkRequest(@NotBlank @Size(max=20000) String idToken,@NotBlank @Size(max=200) String currentPassword){}
 @PostMapping(value="/api/auth/google/link",consumes=MediaType.APPLICATION_JSON_VALUE)
 public ResponseEntity<AuthResponse> link(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody LinkRequest body,HttpServletRequest request){config.requireOrigin(request.getHeader("Origin"));String binding=null;if(request.getCookies()!=null)for(var cookie:request.getCookies())if("teasmart_google_challenge".equals(cookie.getName()))binding=cookie.getValue();var result=service.link(Long.valueOf(jwt.getSubject()),body.currentPassword(),body.idToken(),binding);var cookie=ResponseCookie.from("teasmart_google_challenge","").path("/api/auth/google").httpOnly(true).sameSite("Strict").secure(request.isSecure()||request.getHeader("Origin").startsWith("https://")).maxAge(0).build();return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE,cookie.toString()).body(result);}
}
