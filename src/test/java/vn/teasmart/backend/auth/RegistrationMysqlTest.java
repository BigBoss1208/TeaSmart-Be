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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="PAYMENT_TEST_SERVER_URL", matches="jdbc:mysql://.*")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RegistrationMysqlTest {
    static final String DB="teasmart_auth03_test_"+UUID.randomUUID().toString().replace("-", "");
    static final String PASSWORD=UUID.randomUUID().toString();
    static final JsonMapper JSON=JsonMapper.builder().build();
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;

    static Connection connection(String database) throws Exception {
        return DriverManager.getConnection(System.getenv("PAYMENT_TEST_SERVER_URL")+database,
                System.getenv("PAYMENT_TEST_USERNAME"),System.getenv("PAYMENT_TEST_PASSWORD"));
    }
    static void migration(Statement s) throws Exception {
        String sql=Files.readString(Path.of("database/migrations/auth03_add_users_phone_unique.sql"))
                .replaceAll("(?m)^--.*$", "").replaceAll("(?m)^DELIMITER.*$", "");
        for(String part:sql.split("\\$\\$")) if(!part.isBlank()) s.execute(part.trim());
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) throws Exception {
        r.add("teasmart.seller.enabled",()->false);
        String server=System.getenv("PAYMENT_TEST_SERVER_URL");
        if(!server.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1):[0-9]+/")) throw new IllegalArgumentException("Local test server only.");
        try(var c=connection("");var s=c.createStatement()) {
            s.execute("CREATE DATABASE `"+DB+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        try(var c=connection(DB);var s=c.createStatement()) {
            String schema=Files.readString(Path.of("database/schema.sql"))
                    .replace(",\n  CONSTRAINT `uk_users_phone` UNIQUE (`phone`)", "")
                    .replace(",\r\n  CONSTRAINT `uk_users_phone` UNIQUE (`phone`)", "");
            for(String part:schema.replaceAll("(?m)^--.*$", "").split(";")) {
                String sql=part.trim();if(sql.isEmpty()||sql.startsWith("CREATE DATABASE")||sql.startsWith("USE "))continue;
                if(!sql.startsWith("CREATE TABLE"))throw new IllegalArgumentException("Unexpected schema fixture.");
                s.execute(sql);
            }
            String hash=new BCryptPasswordEncoder().encode(PASSWORD);
            try(var p=c.prepareStatement("INSERT INTO users(full_name,email,password_hash,phone,role,status,created_at,updated_at) VALUES('Legacy fixture',?,?,?,?,'ACTIVE','2001-01-01','2001-01-01')")) {
                for(String[] fixture:new String[][]{{"old-customer@example.invalid","legacy-invalid","CUSTOMER"},{"old-admin@example.invalid",null,"ADMIN"},{"nullable@example.invalid",null,"CUSTOMER"}}) {
                    p.setString(1,fixture[0]);p.setString(2,hash);p.setString(3,fixture[1]);p.setString(4,fixture[2]);p.executeUpdate();
                }
            }
            // Verify duplicate guard without touching any real database or account.
            s.execute("UPDATE users SET phone='legacy-invalid' WHERE email='nullable@example.invalid'");
            assertThrows(SQLException.class,()->migration(s));
            s.execute("DROP PROCEDURE auth03_add_users_phone_unique");
            s.execute("UPDATE users SET phone=NULL WHERE email='nullable@example.invalid'");
            s.execute("UPDATE users SET phone='+84912345678' WHERE email='nullable@example.invalid'");
            assertThrows(SQLException.class,()->migration(s));
            s.execute("DROP PROCEDURE auth03_add_users_phone_unique");
            s.execute("UPDATE users SET phone=NULL WHERE email='nullable@example.invalid'");
            migration(s);
            migration(s); // Idempotent success.
            try(var rs=s.executeQuery("SELECT COUNT(*) FROM users WHERE phone IS NULL")){rs.next();assertEquals(2,rs.getInt(1));}
            try(var rs=s.executeQuery("SELECT COUNT(*) FROM users WHERE phone='legacy-invalid' AND created_at='2001-01-01'")){rs.next();assertEquals(1,rs.getInt(1));}
            TestSchemaSupport.applyCheckoutMigration(c);
        }
        byte[] secret=new byte[48];new java.security.SecureRandom().nextBytes(secret);
        r.add("spring.datasource.url",()->server+DB);
        r.add("spring.datasource.username",()->System.getenv("PAYMENT_TEST_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv("PAYMENT_TEST_PASSWORD"));
        r.add("teasmart.jwt.secret-base64",()->Base64.getEncoder().encodeToString(secret));
        r.add("teasmart.jwt.issuer",()->"auth03-tests");
        r.add("teasmart.vnpay.enabled",()->false);
    }
    HttpResponse<String> call(String path, Map<String,Object> body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .timeout(java.time.Duration.ofSeconds(20)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    Map<String,Object> registration(String email,Object phone){var body=new HashMap<String,Object>();body.put("email",email);body.put("phone",phone);body.put("fullName","Test fixture");body.put("password",PASSWORD);return body;}
    @Test @Order(1) void registrationDuplicatesValidationAndLegacyLogin() throws Exception {
        var created=call("/api/auth/register",registration("fresh@example.invalid"," +84 912 345 678 "));
        assertEquals(201,created.statusCode());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE phone='0912345678'",Integer.class));
        var email=call("/api/auth/register",registration(" FRESH@example.invalid ","0987654321"));
        assertEquals(409,email.statusCode());assertEquals("DUPLICATE_EMAIL",JSON.readTree(email.body()).get("code").stringValue());
        var phone=call("/api/auth/register",registration("another@example.invalid","0912345678"));
        assertEquals(409,phone.statusCode());assertEquals("DUPLICATE_PHONE",JSON.readTree(phone.body()).get("code").stringValue());
        for(Object invalid:Arrays.asList(null,"","0912-345678","0123456789")) assertEquals(400,call("/api/auth/register",registration("invalid@example.invalid",invalid)).statusCode());
        assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM users",Integer.class));
        for(String legacy:List.of("old-customer@example.invalid","old-admin@example.invalid")) {
            var result=call("/api/auth/login",Map.of("email",legacy,"password",PASSWORD));assertEquals(200,result.statusCode());
        }
        assertEquals(401,call("/api/auth/login",Map.of("email","old-customer@example.invalid","password","wrong-password")).statusCode());
    }
    @Test @Order(2) void concurrentRegistrationCanonicalPhone() throws Exception {
        var pool=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try {
            var results=new ArrayList<Future<Integer>>();
            for(int i=0;i<2;i++){final int index=i;results.add(pool.submit(()->{ready.countDown();go.await();return call("/api/auth/register",registration("race"+index+"@example.invalid",index==0?"0987654321":"+84987654321")).statusCode();}));}
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();var codes=new ArrayList<Integer>();for(var result:results)codes.add(result.get(30,TimeUnit.SECONDS));Collections.sort(codes);assertEquals(List.of(201,409),codes);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE phone='0987654321'",Integer.class));
        } finally {pool.shutdownNow();}
    }
    HttpResponse<String> get(String path, String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .timeout(java.time.Duration.ofSeconds(20)).header("Authorization", "Bearer "+token)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test @Order(3) void emailPhoneLoginAndSecurityRegression() throws Exception {
        String customerId = null;
        String customerToken = null;
        for (String identifier : List.of("fresh@example.invalid", " FRESH@example.invalid ",
                "0912345678", "+84912345678", " +84 912 345 678 ")) {
            var result=call("/api/auth/login",Map.of("email",identifier,"password",PASSWORD));
            assertEquals(200,result.statusCode());
            var body=JSON.readTree(result.body());
            String id=body.get("user").get("userId").toString();
            if(customerId==null)customerId=id;else assertEquals(customerId,id);
            assertEquals("CUSTOMER",body.get("user").get("role").stringValue());
            customerToken=body.get("accessToken").stringValue();
            assertEquals(200,get("/api/users/me",customerToken).statusCode());
            assertEquals(403,get("/api/admin/categories",customerToken).statusCode());
        }
        for (String identifier : List.of("missing@example.invalid", "0900000000"))
            assertEquals(401,call("/api/auth/login",Map.of("email",identifier,"password",PASSWORD)).statusCode());
        for (String identifier : List.of("fresh@example.invalid", "0912345678"))
            assertEquals(401,call("/api/auth/login",Map.of("email",identifier,"password","wrong-password")).statusCode());
        for (String identifier : List.of("", " ", "not-email", "0912-345678", "0123456789"))
            assertEquals(400,call("/api/auth/login",Map.of("email",identifier,"password",PASSWORD)).statusCode());
        var nullBody=new HashMap<String,Object>();nullBody.put("email",null);nullBody.put("password",PASSWORD);
        assertEquals(400,call("/api/auth/login",nullBody).statusCode());
        assertEquals(401,call("/api/auth/login",Map.of("email","0912345678","password","x".repeat(73))).statusCode());
        for(String legacy:List.of("old-customer@example.invalid","old-admin@example.invalid","nullable@example.invalid"))
            assertEquals(200,call("/api/auth/login",Map.of("email",legacy,"password",PASSWORD)).statusCode());
        jdbc.update("UPDATE users SET phone='0977777777' WHERE email='old-admin@example.invalid'");
        for(String identifier:List.of("old-admin@example.invalid","0977777777","+84977777777")) {
            var result=call("/api/auth/login",Map.of("email",identifier,"password",PASSWORD));
            assertEquals(200,result.statusCode());var body=JSON.readTree(result.body());
            assertEquals("ADMIN",body.get("user").get("role").stringValue());
            String token=body.get("accessToken").stringValue();
            assertEquals(200,get("/api/users/me",token).statusCode());
            assertEquals(200,get("/api/admin/categories",token).statusCode());
            assertEquals(403,get("/api/cart",token).statusCode());
        }
        jdbc.update("UPDATE users SET status='INACTIVE' WHERE email='fresh@example.invalid'");
        for(String identifier:List.of("fresh@example.invalid","0912345678"))
            assertEquals(401,call("/api/auth/login",Map.of("email",identifier,"password",PASSWORD)).statusCode());
        assertEquals(401,get("/api/users/me",customerToken).statusCode());
    }
    @AfterAll static void cleanup() throws Exception {
        if(!DB.matches("teasmart_auth03_test_[a-f0-9]{32}"))throw new IllegalStateException("Unsafe cleanup target.");
        try(var c=connection("");var s=c.createStatement()){s.execute("DROP DATABASE `"+DB+"`");}
    }
}
