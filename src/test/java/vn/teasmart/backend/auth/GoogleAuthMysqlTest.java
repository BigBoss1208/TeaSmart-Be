package vn.teasmart.backend.auth;
import vn.teasmart.backend.TestSchemaSupport;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import tools.jackson.databind.json.JsonMapper;
import vn.teasmart.backend.security.GoogleTokenVerifier;
import vn.teasmart.backend.exception.GoogleAuthException;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="PAYMENT_TEST_SERVER_URL",matches="jdbc:mysql://.*")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(GoogleAuthMysqlTest.FakeConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GoogleAuthMysqlTest {
    static final String DB="teasmart_google_test_"+UUID.randomUUID().toString().replace("-", "");
    static final JsonMapper JSON=JsonMapper.builder().build();
    static record Verified(GoogleTokenVerifier.Identity identity,String nonce) { }
    static final Map<String,Verified> TOKENS=new ConcurrentHashMap<>();
    @TestConfiguration static class FakeConfig {
        @Bean @Primary GoogleTokenVerifier fakeVerifier() { return (token,nonce)->{
            if("offline-fixture".equals(token))throw GoogleAuthException.unavailable();
            var value=TOKENS.get(token);
            if(value==null||!nonce.equals(value.nonce()))throw GoogleAuthException.failed();
            return value.identity();
        }; }
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    static Connection connection(String db) throws SQLException {
        String server=System.getenv("PAYMENT_TEST_SERVER_URL");
        if(!server.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1):[0-9]+/"))throw new IllegalArgumentException("Only isolated local test server.");
        return DriverManager.getConnection(server+db,System.getenv("PAYMENT_TEST_USERNAME"),System.getenv("PAYMENT_TEST_PASSWORD"));
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) throws Exception {
        r.add("teasmart.seller.enabled",()->false);
        try(var c=connection("");var s=c.createStatement()){s.execute("CREATE DATABASE `"+DB+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");}
        try(var c=connection(DB);var s=c.createStatement()) {
            for(String part:Files.readString(Path.of("database/schema.sql")).replaceAll("(?m)^--.*$", "").split(";")) {
                String sql=part.trim();if(sql.isEmpty()||sql.startsWith("CREATE DATABASE")||sql.startsWith("USE "))continue;
                if(!sql.startsWith("CREATE TABLE"))throw new IllegalArgumentException("Unexpected fixture DDL.");s.execute(sql);
            }
            String migration=Files.readString(Path.of("database/migrations/auth04_add_google_identity.sql"))
                    .replaceAll("(?m)^--.*$", "").replaceAll("(?m)^DELIMITER.*$", "");
            for(String part:migration.split("\\$\\$"))if(!part.isBlank())s.execute(part.trim());
            TestSchemaSupport.applyCheckoutMigration(c);
        }
        byte[] secret=new byte[32];new java.security.SecureRandom().nextBytes(secret);
        r.add("spring.datasource.url",()->System.getenv("PAYMENT_TEST_SERVER_URL")+DB);
        r.add("spring.datasource.username",()->System.getenv("PAYMENT_TEST_USERNAME"));r.add("spring.datasource.password",()->System.getenv("PAYMENT_TEST_PASSWORD"));
        r.add("teasmart.jwt.secret-base64",()->Base64.getEncoder().encodeToString(secret));r.add("teasmart.jwt.issuer",()->"google-tests");
        r.add("teasmart.google.enabled",()->true);r.add("teasmart.google.client-id",()->"fixture-client");
        r.add("teasmart.google.allowed-origins",()->"http://localhost:3000,https://shop.example.com");r.add("teasmart.vnpay.enabled",()->false);
    }
    HttpResponse<String> post(String path,Object body,String cookie,String origin) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(java.time.Duration.ofSeconds(20));
        if(origin!=null)b.header("Origin",origin);if(cookie!=null)b.header("Cookie",cookie);
        if(body!=null)b.header("Content-Type","application/json");
        return HttpClient.newHttpClient().send(b.POST(body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    record Attempt(String token,String cookie,String nonce) { }
    Attempt attempt(String sub,String email) throws Exception {
        var c=post("/api/auth/google/challenge",null,null,"http://localhost:3000");assertEquals(200,c.statusCode());
        String nonce=JSON.readTree(c.body()).get("nonce").stringValue();String cookie=c.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
        String token=UUID.randomUUID().toString();TOKENS.put(token,new Verified(new GoogleTokenVerifier.Identity(sub,email,"Fixture"),nonce));
        return new Attempt(token,cookie,nonce);
    }
    HttpResponse<String> login(Attempt a) throws Exception { return post("/api/auth/google",Map.of("idToken",a.token()),a.cookie(),"http://localhost:3000"); }
    @Test @Order(1) void newAndReturningUserJwtAndPasswordNull() throws Exception {
        var a=attempt("new-sub","new@example.invalid");var result=login(a);assertEquals(200,result.statusCode());
        var body=JSON.readTree(result.body());long id=body.get("user").get("userId").longValue();
        assertEquals("CUSTOMER",body.get("user").get("role").stringValue());assertEquals("Bearer",body.get("tokenType").stringValue());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE user_id=? AND password_hash IS NULL AND phone IS NULL AND status='ACTIVE'",Integer.class,id));
        String token=body.get("accessToken").stringValue();
        var me=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/users/me"))
                .header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,me.statusCode());assertEquals(id,JSON.readTree(me.body()).get("userId").longValue());
        var admin=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/admin/categories"))
                .header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.discarding());assertEquals(403,admin.statusCode());
        assertEquals(401,login(a).statusCode());
        var returning=login(attempt("new-sub","changed@example.invalid"));assertEquals(200,returning.statusCode());
        assertEquals("new@example.invalid",JSON.readTree(returning.body()).get("user").get("email").stringValue());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE google_sub='new-sub'",Integer.class));
        assertEquals(401,post("/api/auth/login",Map.of("email","new@example.invalid","password","fixture-not-real"),null,null).statusCode());
    }
    @Test @Order(2) void conflictAdminInactiveAndInvalidRequests() throws Exception {
        jdbc.update("INSERT INTO users(full_name,email,password_hash,role,status,created_at,updated_at) VALUES('Fixture','password@example.invalid','fixture-unusable-hash','CUSTOMER','ACTIVE','2001-01-01','2001-01-01')");
        var conflict=login(attempt("not-linked","password@example.invalid"));assertEquals(409,conflict.statusCode());
        assertEquals("GOOGLE_LINK_REQUIRED",JSON.readTree(conflict.body()).get("code").stringValue());
        for(String[] v:new String[][]{{"admin-sub","ADMIN","ACTIVE"},{"inactive-sub","CUSTOMER","INACTIVE"}}) {
            jdbc.update("INSERT INTO users(full_name,email,google_sub,role,status,created_at,updated_at) VALUES('Fixture',?,?,?,?,'2001-01-01','2001-01-01')",v[0]+"@example.invalid",v[0],v[1],v[2]);
            assertEquals(401,login(attempt(v[0],v[0]+"@example.invalid")).statusCode());
        }
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class);
        assertEquals(401,post("/api/auth/google/challenge",null,null,null).statusCode());
        assertEquals(401,post("/api/auth/google/challenge",null,null,"https://evil.example").statusCode());
        var c=attempt("unused","unused@example.invalid");
        assertEquals(401,post("/api/auth/google",Map.of("idToken",c.token()),null,"http://localhost:3000").statusCode());
        assertEquals(400,post("/api/auth/google",Map.of(),c.cookie(),"http://localhost:3000").statusCode());
        assertEquals(400,post("/api/auth/google",Map.of("idToken",c.token(),"role","ADMIN"),c.cookie(),"http://localhost:3000").statusCode());
        var badBody=post("/api/auth/google",Map.of("idToken",c.token(),"userId",1),c.cookie(),"http://localhost:3000");
        assertEquals(400,badBody.statusCode());assertEquals("VALIDATION_ERROR",JSON.readTree(badBody.body()).get("code").stringValue());
        assertEquals(401,post("/api/auth/google",Map.of("idToken","invalid-fixture"),c.cookie(),"http://localhost:3000").statusCode());
        assertEquals(503,post("/api/auth/google",Map.of("idToken","offline-fixture"),c.cookie(),"http://localhost:3000").statusCode());
        TOKENS.put(c.token(),new Verified(new GoogleTokenVerifier.Identity("unused","unused@example.invalid","Fixture"),"wrong-nonce"));
        assertEquals(401,login(c).statusCode());assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class));
        var secure=post("/api/auth/google/challenge",null,null,"https://shop.example.com");
        String header=secure.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(header.contains("Secure"));assertTrue(header.contains("HttpOnly"));assertTrue(header.contains("SameSite=Strict"));
    }
    @Test @Order(3) void concurrentNonceAndAccountCreation() throws Exception {
        var pool=Executors.newFixedThreadPool(4);
        try {
            var same=attempt("nonce-race","nonce-race@example.invalid");
            var codes=new ArrayList<Integer>();for(var f:pool.invokeAll(List.<Callable<Integer>>of(()->login(same).statusCode(),()->login(same).statusCode())))codes.add(f.get());
            Collections.sort(codes);assertEquals(List.of(200,401),codes);
            var a=attempt("creation-race","creation-race@example.invalid");var b=attempt("creation-race","creation-race@example.invalid");
            for(var f:pool.invokeAll(List.<Callable<Integer>>of(()->login(a).statusCode(),()->login(b).statusCode())))assertEquals(200,f.get());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE google_sub='creation-race'",Integer.class));
            var x=attempt("email-race-one","email-race@example.invalid");var y=attempt("email-race-two","email-race@example.invalid");
            codes.clear();for(var f:pool.invokeAll(List.<Callable<Integer>>of(()->login(x).statusCode(),()->login(y).statusCode())))codes.add(f.get());
            Collections.sort(codes);assertEquals(List.of(200,409),codes);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email='email-race@example.invalid'",Integer.class));
        }finally{pool.shutdownNow();}
    }
    @AfterAll static void cleanup() throws Exception {
        TOKENS.clear();if(!DB.matches("teasmart_google_test_[a-f0-9]{32}"))throw new IllegalStateException("Unsafe test target.");
        try(var c=connection("");var s=c.createStatement()){s.execute("DROP DATABASE `"+DB+"`");}
    }
}
