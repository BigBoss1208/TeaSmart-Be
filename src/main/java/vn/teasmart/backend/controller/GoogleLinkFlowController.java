package vn.teasmart.backend.controller;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import vn.teasmart.backend.service.GoogleLinkFlow;
import vn.teasmart.backend.dto.response.AuthResponse;

@RestController
public class GoogleLinkFlowController {
    private static final String COOKIE="teasmart_google_link";
    private final GoogleLinkFlow flow;
    public GoogleLinkFlowController(GoogleLinkFlow flow){this.flow=flow;}
    static String binding(HttpServletRequest r){if(r.getCookies()!=null)for(var c:r.getCookies())if(COOKIE.equals(c.getName()))return c.getValue();return null;}
    private String cookie(String value,long age,HttpServletRequest r){return ResponseCookie.from(COOKIE,value).path("/api/auth/google/link").httpOnly(true).sameSite("Lax").secure(r.isSecure()||String.valueOf(r.getHeader("Origin")).startsWith("https://")).maxAge(age).build().toString();}
    @PostMapping("/api/auth/google/link/start") public ResponseEntity<GoogleLinkFlow.Started> start(@AuthenticationPrincipal Jwt jwt,HttpServletRequest request){var result=flow.start(jwt,request.getHeader("Origin"),binding(request));return ResponseEntity.ok().header("Cache-Control","no-store").header(HttpHeaders.SET_COOKIE,cookie(result.binding(),300,request)).body(result.response());}
    @GetMapping("/api/auth/google/link/callback") public ResponseEntity<Void> callback(@RequestParam(required=false) String state,@RequestParam(required=false) String code,@RequestParam(required=false) String error,HttpServletRequest request){String target=flow.callback(binding(request),state,code,error);return ResponseEntity.status(303).header(HttpHeaders.LOCATION,target).header("Cache-Control","no-store").header("Referrer-Policy","no-referrer").build();}
    @PostMapping("/api/auth/google/link/complete") public ResponseEntity<AuthResponse> complete(@AuthenticationPrincipal Jwt jwt,HttpServletRequest request){var result=flow.complete(jwt,binding(request),request.getHeader("Origin"));return ResponseEntity.ok().header("Cache-Control","no-store").header(HttpHeaders.SET_COOKIE,cookie("",0,request)).body(result);}
}