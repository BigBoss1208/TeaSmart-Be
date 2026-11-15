package vn.teasmart.backend.service;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import vn.teasmart.backend.config.GoogleAuthConfig;
import vn.teasmart.backend.exception.GoogleAuthException;

/** Confidential-client code exchange. Secrets and Google responses are never logged. */
@Component
public class GoogleCodeClient {
    private final GoogleAuthConfig config;
    private final String secret,redirect;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public GoogleCodeClient(GoogleAuthConfig config,@Value("${teasmart.google.link.client-secret:}") String secret,@Value("${teasmart.google.link.redirect-uri:}") String redirect){
        this.config=config;this.secret=secret;this.redirect=redirect;
        if(!redirect.isBlank()) {var u=URI.create(redirect);boolean local=java.util.Set.of("localhost","127.0.0.1").contains(u.getHost());if(u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||!("https".equals(u.getScheme())||local&&"http".equals(u.getScheme()))||!"/api/auth/google/link/callback".equals(u.getPath()))throw new IllegalArgumentException("Invalid Google linking redirect configuration");}
    }
    public void available(){if(secret.isBlank()||redirect.isBlank())throw GoogleAuthException.unavailable();}
    public String authorization(String state,String nonce,String challenge){available();return "https://accounts.google.com/o/oauth2/v2/auth?"+form(Map.of("client_id",config.clientId(),"redirect_uri",redirect,"response_type","code","scope","openid email profile","state",state,"nonce",nonce,"code_challenge",challenge,"code_challenge_method","S256","prompt","select_account"));}
    static String form(Map<String,String> values){return values.entrySet().stream().map(e->URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)+"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).collect(Collectors.joining("&"));}
    public String exchange(String code,String verifier){
        available();if(code==null||code.isBlank()||code.length()>4096)throw GoogleAuthException.failed();
        try{var body=form(Map.of("client_id",config.clientId(),"client_secret",secret,"redirect_uri",redirect,"grant_type","authorization_code","code",code,"code_verifier",verifier));
            var request=HttpRequest.newBuilder(URI.create("https://oauth2.googleapis.com/token")).timeout(Duration.ofSeconds(15)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200||response.body().length()>65536)throw GoogleAuthException.failed();
            var token=JsonMapper.builder().build().readTree(response.body()).get("id_token");
            if(token==null||!token.isString()||token.stringValue().length()>16384)throw GoogleAuthException.failed();return token.stringValue();
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw GoogleAuthException.unavailable();}catch(Exception e){throw GoogleAuthException.failed();}
    }
}