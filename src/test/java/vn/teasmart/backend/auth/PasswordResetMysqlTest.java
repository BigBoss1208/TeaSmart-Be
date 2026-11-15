package vn.teasmart.backend.auth;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.*;
import org.springframework.test.context.*;
import tools.jackson.databind.json.JsonMapper;
import vn.teasmart.backend.TestSchemaSupport;
import vn.teasmart.backend.service.PasswordResetService;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="PAYMENT_TEST_SERVER_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:33377/")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PasswordResetMysqlTest.MailConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PasswordResetMysqlTest {
    static final String DB="teasmart_reset_test_"+UUID.randomUUID().toString().replace("-", "");
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final FakeSmtp SMTP=new FakeSmtp();
    @TestConfiguration static class MailConfig {
        @Bean @Primary JavaMailSender localSmtp() {
            var sender=new JavaMailSenderImpl(); sender.setHost("127.0.0.1"); sender.setPort(SMTP.port());
            sender.getJavaMailProperties().put("mail.smtp.timeout","2000");
            sender.getJavaMailProperties().put("mail.smtp.connectiontimeout","2000");
            return sender;
        }
    }
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordResetService service;
    static Connection connection(String db) throws SQLException {
        if(!"jdbc:mysql://127.0.0.1:33377/".equals(System.getenv("PAYMENT_TEST_SERVER_URL")))throw new IllegalStateException("Unsafe server");
        return DriverManager.getConnection(System.getenv("PAYMENT_TEST_SERVER_URL")+db,System.getenv("PAYMENT_TEST_USERNAME"),System.getenv("PAYMENT_TEST_PASSWORD"));
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) throws Exception {
        try(var c=connection("");var s=c.createStatement()) {
            var rs=s.executeQuery("SELECT @@port,@@datadir");rs.next();
            if(rs.getInt(1)!=33377 || !rs.getString(2).replace('\\','/').contains("checkout-demo/data"))throw new IllegalStateException("Not isolated test datadir");
            s.execute("CREATE DATABASE `"+DB+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        try(var c=connection(DB);var s=c.createStatement()) {
            for(String part:Files.readString(Path.of("database/schema.sql")).replaceAll("(?m)^--.*$", "").split(";")) {
                String sql=part.trim();if(sql.isEmpty()||sql.startsWith("CREATE DATABASE")||sql.startsWith("USE "))continue;
                if(!sql.startsWith("CREATE TABLE"))throw new IllegalArgumentException("Unexpected fixture DDL");s.execute(sql);
            }
            String google=Files.readString(Path.of("database/migrations/auth04_add_google_identity.sql")).replaceAll("(?m)^--.*$", "").replaceAll("(?m)^DELIMITER.*$", "");
            for(String part:google.split("\\$\\$"))if(!part.isBlank())s.execute(part.trim());
            TestSchemaSupport.applyCheckoutMigration(c);
            s.execute(Files.readString(Path.of("database/migrations/auth05_password_reset_tokens.sql")).replaceAll("(?m)^--.*$", "").trim());
        }
        try(var c=connection(DB);var s=c.createStatement()) {
            s.execute(Files.readString(Path.of("database/migrations/auth06_password_reset_otp.sql")).replaceAll("(?m)^--.*$", "").trim());
        }
        byte[] secret=new byte[32];new java.security.SecureRandom().nextBytes(secret);
        r.add("spring.config.import",()->"");r.add("spring.datasource.url",()->System.getenv("PAYMENT_TEST_SERVER_URL")+DB);
        r.add("spring.datasource.username",()->System.getenv("PAYMENT_TEST_USERNAME"));r.add("spring.datasource.password",()->System.getenv("PAYMENT_TEST_PASSWORD"));
        r.add("teasmart.jwt.secret-base64",()->Base64.getEncoder().encodeToString(secret));r.add("teasmart.jwt.issuer",()->"reset-tests");
        r.add("teasmart.google.enabled",()->false);r.add("teasmart.seller.enabled",()->false);r.add("teasmart.vnpay.enabled",()->false);
        r.add("teasmart.password-reset.enabled",()->true);r.add("teasmart.password-reset.frontend-url",()->"http://localhost:3001");
        r.add("teasmart.mail.enabled",()->true);r.add("teasmart.mail.from",()->"test@example.invalid");
    }
    HttpResponse<String> post(String path,Object body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/auth/"+path))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    void register(String email,String phone) throws Exception {
        assertEquals(201,post("register",Map.of("fullName","Reset Fixture","email",email,"phone",phone,"password","Original123!")).statusCode());
    }
    void cooldown(String email) { jdbc.update("UPDATE password_reset_tokens SET issued_at=UTC_TIMESTAMP()-INTERVAL 2 MINUTE WHERE user_id=(SELECT user_id FROM users WHERE email=?)",email); }
    record Challenge(String id,String otp) { }
    Challenge request(String email) throws Exception {
        SMTP.messages.clear();String id=service.newChallenge();service.request(email,id);return new Challenge(id,mailOtp());
    }
    String mailOtp() throws Exception {
        String raw=SMTP.messages.poll(8,TimeUnit.SECONDS);assertNotNull(raw,"No mail received");
        var mime=new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
        assertEquals("Mã xác minh đặt lại mật khẩu TeaSmart",mime.getSubject());
        String html=mime.getContent().toString();assertTrue(html.contains("5 phút"));assertFalse(html.contains("#token="));
        var matcher=Pattern.compile("<strong>([0-9]{6})</strong>").matcher(html);assertTrue(matcher.find());return matcher.group(1);
    }
    HttpResponse<String> verify(Challenge c) throws Exception { return post("reset-password/verify",Map.of("challenge",c.id(),"otp",c.otp())); }
    String grant(Challenge c) throws Exception {var response=verify(c);assertEquals(200,response.statusCode());return JSON.readTree(response.body()).path("token").asString();}
    HttpResponse<String> reset(String token,String password,String confirm) throws Exception {return post("reset-password",Map.of("token",token,"password",password,"confirmPassword",confirm));}
    @Test @Order(1) void fullApiOtpResetAndLogin() throws Exception {
        String email="reset-one@example.invalid";register(email,"0987000001");
        SMTP.messages.clear();var response=post("forgot-password",Map.of("email",email));assertEquals(202,response.statusCode());
        var c=new Challenge(JSON.readTree(response.body()).path("challenge").asString(),mailOtp());
        String stored=jdbc.queryForObject("SELECT otp_hash FROM password_reset_tokens",String.class);
        assertTrue(stored.startsWith("$2"));assertNotEquals(c.otp(),stored);
        assertEquals(400,reset(c.id(),"Bypass123!","Bypass123!").statusCode());
        String token=grant(c);assertEquals(400,verify(c).statusCode());
        assertEquals(400,reset(token,"short","short").statusCode());
        assertEquals(400,reset(token,"NewPassword123!","Mismatch123!").statusCode());
        assertEquals(400,reset(token,"漢".repeat(30),"漢".repeat(30)).statusCode());
        assertEquals(200,reset(token,"NewPassword123!","NewPassword123!").statusCode());
        assertEquals(400,reset(token,"Another123!","Another123!").statusCode());
        assertEquals(200,post("login",Map.of("email",email,"password","NewPassword123!")).statusCode());
        assertEquals(401,post("login",Map.of("email",email,"password","Original123!")).statusCode());
    }
    @Test @Order(2) void wrongFiveAttemptsExpiryResendAndOldLinks() throws Exception {
        String email="reset-two@example.invalid";register(email,"0987000002");var c=request(email);
        String wrong=c.otp().equals("000000")?"000001":"000000";
        for(int i=0;i<5;i++)assertEquals(400,verify(new Challenge(c.id(),wrong)).statusCode());
        assertEquals(400,verify(c).statusCode());
        assertEquals(5,jdbc.queryForObject("SELECT otp_attempts FROM password_reset_tokens WHERE token_hash=?",Integer.class,PasswordResetService.hash(c.id())));
        cooldown(email);var expired=request(email);
        jdbc.update("UPDATE password_reset_tokens SET expires_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE token_hash=?",PasswordResetService.hash(expired.id()));
        assertEquals(400,verify(expired).statusCode());
        cooldown(email);var replacement=request(email);assertEquals(400,verify(expired).statusCode());
        String token=grant(replacement);
        jdbc.update("UPDATE password_reset_tokens SET expires_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE token_hash=?",PasswordResetService.hash(token));
        assertEquals(400,reset(token,"Expired123!","Expired123!").statusCode());
        cooldown(email);var newer=request(email);assertEquals(400,reset(token,"Expired123!","Expired123!").statusCode());
        // Model a pre-OTP reset link: no verified marker, even with a future expiry.
        jdbc.update("UPDATE password_reset_tokens SET otp_hash=NULL WHERE token_hash=?",PasswordResetService.hash(newer.id()));
        assertEquals(400,reset(newer.id(),"Legacy123!","Legacy123!").statusCode());
        assertEquals(400,verify(newer).statusCode());
    }
    @Test @Order(3) void concurrentOtpAndGrantAreSingleUse() throws Exception {
        String email="reset-race@example.invalid";register(email,"0987000005");var c=request(email);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var replies=pool.invokeAll(List.<Callable<HttpResponse<String>>>of(()->verify(c),()->verify(c)));
            var codes=new ArrayList<Integer>();String token=null;
            for(var f:replies){var r=f.get();codes.add(r.statusCode());if(r.statusCode()==200)token=JSON.readTree(r.body()).path("token").asString();}
            Collections.sort(codes);assertEquals(List.of(200,400),codes);final String grant=token;
            codes.clear();for(var f:pool.invokeAll(List.<Callable<Integer>>of(()->reset(grant,"Concurrent123!","Concurrent123!").statusCode(),()->reset(grant,"Concurrent123!","Concurrent123!").statusCode())))codes.add(f.get());
            Collections.sort(codes);assertEquals(List.of(200,400),codes);
        }finally{pool.shutdownNow();}
    }
    @Test @Order(3) void concurrentIssuanceAndResendRevokePreviousGrant() throws Exception {
        String email="reset-send-race@example.invalid";register(email,"0987000006");SMTP.messages.clear();
        String a=service.newChallenge(),b=service.newChallenge();
        var pool=Executors.newFixedThreadPool(2);
        try {for(var f:pool.invokeAll(List.<Callable<Void>>of(()->{service.request(email,a);return null;},()->{service.request(email,b);return null;})))f.get();}
        finally{pool.shutdownNow();}
        String otp=mailOtp();assertNull(SMTP.messages.poll(200,TimeUnit.MILLISECONDS));
        String stored=jdbc.queryForObject("SELECT token_hash FROM password_reset_tokens t JOIN users u ON t.user_id=u.user_id WHERE u.email=?",String.class,email);
        String selected=PasswordResetService.hash(a).equals(stored)?a:b;
        String token=grant(new Challenge(selected,otp));
        cooldown(email);var next=request(email);
        assertEquals(400,reset(token,"Revoked123!","Revoked123!").statusCode());
        assertEquals(400,verify(new Challenge(selected,otp)).statusCode());
        if(!otp.equals(next.otp()))assertEquals(400,verify(new Challenge(next.id(),otp)).statusCode());
        assertNotNull(grant(next));
        assertEquals(200,post("login",Map.of("email",email,"password","Original123!")).statusCode());
    }
    @Test @Order(4) @EnabledIfEnvironmentVariable(named="RESET_BROWSER_E2E",matches="1")
    void realChromeRecoveryFlow() throws Exception {
        register("reset-browser@example.invalid","0987000004");SMTP.messages.clear();
        Path otpFile=Files.createTempFile("teasmart-otp-e2e-",".tmp");
        try {
            var builder=new ProcessBuilder("node", "tests/password-recovery.browser.cjs").directory(Path.of("..").toFile()).redirectErrorStream(true);
            builder.environment().put("RESET_TEST_BACKEND","http://127.0.0.1:"+port);builder.environment().put("RESET_TEST_OTP_FILE",otpFile.toString());
            var process=builder.start();
            // Wait for the real UI POST, then hand the fixture SMTP mail to the browser harness only.
            String raw=SMTP.messages.poll(90,TimeUnit.SECONDS);assertNotNull(raw,"UI did not request mail");SMTP.messages.add(raw);
            Files.writeString(otpFile,mailOtp());
            if(!process.waitFor(120,TimeUnit.SECONDS)){process.destroyForcibly();fail("Browser timed out");}
            String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);System.out.println(output);assertEquals(0,process.exitValue());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens t JOIN users u ON t.user_id=u.user_id WHERE u.email='reset-browser@example.invalid' AND t.used_at IS NOT NULL",Integer.class));
        } finally {Files.deleteIfExists(otpFile);}
    }
    @Test @Order(5) void unknownIneligibleValidationAndThrottling() throws Exception {
        var unknown=post("forgot-password",Map.of("email","absent@example.invalid"));assertEquals(202,unknown.statusCode());
        assertTrue(unknown.body().contains("Nếu email đã được đăng ký"));
        assertEquals(400,post("forgot-password",Map.of("email","not-an-email")).statusCode());
        assertEquals(400,post("reset-password/verify",Map.of("token","invalid")).statusCode());
        jdbc.update("INSERT INTO users(full_name,email,password_hash,role,status,created_at,updated_at) VALUES('Fixture','inactive@example.invalid','hash','CUSTOMER','INACTIVE',NOW(),NOW()),('Fixture','googleonly@example.invalid',NULL,'CUSTOMER','ACTIVE',NOW(),NOW())");
        service.request("inactive@example.invalid");service.request("googleonly@example.invalid");
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_tokens t JOIN users u ON t.user_id=u.user_id WHERE u.email IN ('inactive@example.invalid','googleonly@example.invalid')",Integer.class));
        boolean limited=false;for(int i=0;i<12;i++)if(post("forgot-password",Map.of("email","absent@example.invalid")).statusCode()==429)limited=true;
        assertTrue(limited);
    }
    @Test @Order(6) void smtpFailureAndCooldownLeavePasswordUntouched() throws Exception {
        register("reset-smtp@example.invalid","0987000003");SMTP.fail=true;
        try {
            assertDoesNotThrow(()->service.request("reset-smtp@example.invalid"));
            String hash=jdbc.queryForObject("SELECT token_hash FROM password_reset_tokens t JOIN users u ON u.user_id=t.user_id WHERE u.email='reset-smtp@example.invalid'",String.class);
            service.request("reset-smtp@example.invalid");
            assertEquals(hash,jdbc.queryForObject("SELECT token_hash FROM password_reset_tokens t JOIN users u ON u.user_id=t.user_id WHERE u.email='reset-smtp@example.invalid'",String.class));
            assertEquals(200,post("login",Map.of("email","reset-smtp@example.invalid","password","Original123!")).statusCode());
        }finally{SMTP.fail=false;}
    }
    @AfterAll static void close() throws Exception { SMTP.close(); /* Retain synthetic schema for inspection; never DROP. */ }
    static class FakeSmtp implements AutoCloseable {
        final ServerSocket server; final BlockingQueue<String> messages=new LinkedBlockingQueue<>(); volatile boolean fail;
        FakeSmtp() {
            try {server=new ServerSocket(0,10,InetAddress.getLoopbackAddress());}
            catch(IOException e){throw new IllegalStateException(e);}
            Thread thread=new Thread(()->{while(!server.isClosed())try(var socket=server.accept()) { handle(socket); }catch(IOException ignored){}},"fixture-smtp");thread.setDaemon(true);thread.start();
        }
        int port(){return server.getLocalPort();}
        void handle(Socket socket)throws IOException {
            socket.setSoTimeout(3000);var in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
            var out=new PrintWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8),true);out.print("220 fixture SMTP\r\n");out.flush();
            String line;while((line=in.readLine())!=null) {
                if(line.startsWith("DATA")) {
                    if(fail){out.print("451 Fixture delivery unavailable\r\n");out.flush();continue;}
                    out.print("354 Send data\r\n");out.flush();StringBuilder data=new StringBuilder();
                    while((line=in.readLine())!=null&&!line.equals("."))data.append(line.startsWith("..")?line.substring(1):line).append("\r\n");messages.add(data.toString());
                } else if(line.startsWith("QUIT")){out.print("221 Bye\r\n");out.flush();break;}
                out.print("250 OK\r\n");out.flush();
            }
        }
        public void close()throws IOException{server.close();}
    }
}
