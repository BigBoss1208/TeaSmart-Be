package vn.teasmart.backend.auth;
import vn.teasmart.backend.TestSchemaSupport;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import vn.teasmart.backend.repository.UserRepository;
import static org.junit.jupiter.api.Assertions.*;

/** Fixture-only schema/identity tests. Never connects to the main database. */
@EnabledIfEnvironmentVariable(named="PAYMENT_TEST_SERVER_URL", matches="jdbc:mysql://.*")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GoogleIdentityMysqlTest {
    static final String DB="teasmart_auth04_test_"+UUID.randomUUID().toString().replace("-", "");
    static final String PASSWORD=UUID.randomUUID().toString();
    static List<String> legacy;
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;

    static Connection connection(String db) throws SQLException {
        String server=System.getenv("PAYMENT_TEST_SERVER_URL");
        if(!server.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1):[0-9]+/"))
            throw new IllegalArgumentException("Local test server only.");
        return DriverManager.getConnection(server+db,System.getenv("PAYMENT_TEST_USERNAME"),System.getenv("PAYMENT_TEST_PASSWORD"));
    }
    static void migration(Statement s) throws Exception {
        String sql=Files.readString(Path.of("database/migrations/auth04_add_google_identity.sql"))
                .replaceAll("(?m)^--.*$", "").replaceAll("(?m)^DELIMITER.*$", "");
        for(String part:sql.split("\\$\\$"))if(!part.isBlank())s.execute(part.trim());
    }
    static List<String> originalRows(Statement s) throws SQLException {
        var rows=new ArrayList<String>();
        try(var rs=s.executeQuery("SELECT user_id,full_name,email,password_hash,phone,role,status,avatar_url,created_at,updated_at FROM users WHERE user_id<=3 ORDER BY user_id")) {
            while(rs.next()) {
                var row=new ArrayList<String>();for(int i=1;i<=10;i++)row.add(rs.getString(i));
                rows.add(row.toString());
            }
        }
        return rows;
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) throws Exception {
        r.add("teasmart.seller.enabled",()->false);
        try(var c=connection("");var s=c.createStatement()) {
            s.execute("CREATE DATABASE `"+DB+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        try(var c=connection(DB);var s=c.createStatement()) {
            String schema=Files.readString(Path.of("database/schema.sql"))
                    .replaceAll("(?m)^.*`google_sub`.*\\R?", "")
                    .replace("`password_hash` VARCHAR(255) NULL", "`password_hash` VARCHAR(255) NOT NULL")
                    .replace("UNIQUE (`phone`),", "UNIQUE (`phone`)");
            for(String part:schema.replaceAll("(?m)^--.*$", "").split(";")) {
                String sql=part.trim();if(sql.isEmpty()||sql.startsWith("CREATE DATABASE")||sql.startsWith("USE "))continue;
                if(!sql.startsWith("CREATE TABLE"))throw new IllegalArgumentException("Unexpected fixture statement.");
                s.execute(sql);
            }
            try(var p=c.prepareStatement("INSERT INTO users(full_name,email,password_hash,phone,role,status,created_at,updated_at) VALUES('Fixture',?,?,?,?,'ACTIVE','2001-01-01','2001-01-01')")) {
                String hash=new BCryptPasswordEncoder().encode(PASSWORD);
                for(String[] u:new String[][]{{"legacy@example.invalid","legacy-invalid","CUSTOMER"},
                        {"admin@example.invalid",null,"ADMIN"},{"phone@example.invalid","0912345678","CUSTOMER"}}) {
                    p.setString(1,u[0]);p.setString(2,hash);p.setString(3,u[1]);p.setString(4,u[2]);p.executeUpdate();
                }
            }
            legacy=originalRows(s);
            // Deliberate schema mismatches only in this new fixture database.
            s.execute("ALTER TABLE users ADD COLUMN google_sub VARCHAR(100) NULL");
            assertThrows(SQLException.class,()->migration(s));
            s.execute("DROP PROCEDURE auth04_add_google_identity");
            try(var rs=s.executeQuery("SELECT is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='users' AND column_name='password_hash'")) {
                rs.next();assertEquals("NO",rs.getString(1));
            }
            s.execute("ALTER TABLE users DROP COLUMN google_sub");
            migration(s);
            if(!legacy.equals(originalRows(s)))throw new IllegalStateException("Migration modified legacy fixture values.");
            TestSchemaSupport.applyCheckoutMigration(c);
        }
        byte[] secret=new byte[32];new java.security.SecureRandom().nextBytes(secret);
        r.add("spring.datasource.url",()->System.getenv("PAYMENT_TEST_SERVER_URL")+DB);
        r.add("spring.datasource.username",()->System.getenv("PAYMENT_TEST_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv("PAYMENT_TEST_PASSWORD"));
        r.add("teasmart.jwt.secret-base64",()->Base64.getEncoder().encodeToString(secret));
        r.add("teasmart.jwt.issuer",()->"auth04a-tests");r.add("teasmart.vnpay.enabled",()->false);
    }
    @Test @Order(1) void metadataAndIdempotency() throws Exception {
        assertEquals("varchar(255):YES:utf8mb4_bin",jdbc.queryForObject("SELECT CONCAT(column_type,':',is_nullable,':',collation_name) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='users' AND column_name='google_sub'",String.class));
        assertEquals("YES",jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='users' AND column_name='password_hash'",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='users' AND index_name='uk_users_google_sub' AND non_unique=0 AND column_name='google_sub' AND sub_part IS NULL",Integer.class));
        try(var c=connection(DB);var s=c.createStatement()) {
            migration(s);assertTrue(legacy.equals(originalRows(s)),"Legacy fixture changed");
            s.execute("ALTER TABLE users DROP INDEX uk_users_google_sub, ADD INDEX uk_users_google_sub(google_sub)");
            assertThrows(SQLException.class,()->migration(s));
            s.execute("DROP PROCEDURE auth04_add_google_identity");
            s.execute("ALTER TABLE users DROP INDEX uk_users_google_sub");
            migration(s);
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE() AND routine_name='auth04_add_google_identity'",Integer.class));
    }
    @Test @Order(2) void binaryUniqueAndNullableIdentity() {
        jdbc.update("UPDATE users SET google_sub='CaseSub' WHERE user_id=1");
        jdbc.update("UPDATE users SET google_sub='casesub' WHERE user_id=2");
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE users SET google_sub='CaseSub' WHERE user_id=3"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE google_sub='CaseSub'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE google_sub='casesub'",Integer.class));
        jdbc.update("UPDATE users SET google_sub=NULL WHERE user_id IN (1,2)");
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE google_sub IS NULL",Integer.class));
    }
    int login(String identifier) throws Exception {
        String body="{\"email\":\""+identifier+"\",\"password\":\""+PASSWORD+"\"}";
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/auth/login"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.discarding()).statusCode();
    }
    @Test @Order(3) void nullPasswordAndExistingAccounts() throws Exception {
        for(String id:List.of("legacy@example.invalid","admin@example.invalid","phone@example.invalid","0912345678","+84912345678"))assertEquals(200,login(id));
        var user=new vn.teasmart.backend.entity.User();user.setFullName("Google fixture");user.setEmail("google@example.invalid");
        user.setGoogleSub("fixture-sub");user.setPasswordHash(null);user.setPhone("0987654321");user.setRole("CUSTOMER");user.setStatus("ACTIVE");
        user.setCreatedAt(java.time.LocalDateTime.of(2001,1,1,0,0));user.setUpdatedAt(user.getCreatedAt());users.saveAndFlush(user);
        assertEquals("fixture-sub",users.findById(user.getUserId()).orElseThrow().getGoogleSub());
        assertEquals(401,login("google@example.invalid"));assertEquals(401,login("0987654321"));
        try(var c=connection(DB);var s=c.createStatement()){assertTrue(legacy.equals(originalRows(s)),"Legacy fixture changed");}
    }
    @AfterAll static void cleanup() throws Exception {
        if(!DB.matches("teasmart_auth04_test_[a-f0-9]{32}"))throw new IllegalStateException("Unsafe cleanup target.");
        try(var c=connection("");var s=c.createStatement()){s.execute("DROP DATABASE `"+DB+"`");}
    }
}
