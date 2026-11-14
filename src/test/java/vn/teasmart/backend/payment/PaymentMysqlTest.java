package vn.teasmart.backend.payment;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.teasmart.backend.service.*;
import vn.teasmart.backend.enums.PaymentStatus;
import static org.junit.jupiter.api.Assertions.*;

/** Optional real MySQL tests: only a newly created, empty, uniquely named test database is used. */
@EnabledIfEnvironmentVariable(named = "PAYMENT_TEST_SERVER_URL", matches = "jdbc:mysql://.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentMysqlTest {
    private static final String DB = "teasmart_payment_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String USER = System.getenv("PAYMENT_TEST_USERNAME");
    private static final String PASSWORD = System.getenv("PAYMENT_TEST_PASSWORD");
    private static final String TEST_PASSWORD = UUID.randomUUID().toString();
    private static final String SECRET = UUID.randomUUID().toString();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> TABLES = new ArrayList<>();
    private static final boolean START_WITHOUT_AUDIT = Boolean.getBoolean("teasmart.payment-test.start-without-audit");
    private static int requests;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.mail.javamail.JavaMailSender mailSender;
    private final List<String> deliveredSubjects = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<Boolean> businessMailReadOnlyTransactions = new java.util.concurrent.CopyOnWriteArrayList<>();
    @Autowired vn.teasmart.backend.notification.OrderNotifications notifications;
    @BeforeEach void localMailSink() {
        deliveredSubjects.clear();
        businessMailReadOnlyTransactions.clear();
        org.mockito.Mockito.when(mailSender.createMimeMessage()).thenAnswer(invocation ->
                new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new Properties())));
        org.mockito.Mockito.doAnswer(invocation -> {
            var message = (jakarta.mail.internet.MimeMessage) invocation.getArgument(0);
            // Login is legitimately read-only. Capture order/welcome checks and assert
            // on the JUnit thread: exceptions in AFTER_COMMIT listeners are swallowed.
            if (!"TeaSmart — Đăng nhập thành công".equals(message.getSubject()))
                businessMailReadOnlyTransactions.add(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            deliveredSubjects.add(message.getSubject()); return null;
        }).when(mailSender).send(org.mockito.ArgumentMatchers.any(jakarta.mail.internet.MimeMessage.class));
    }

    @AfterEach void businessMailUsesWriteTransaction() {
        assertFalse(businessMailReadOnlyTransactions.contains(true), "Order/welcome mail originated from a read-only transaction");
    }

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired VnpayGateway gateway;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    VnpayQueryClient queryClient;
    @Autowired PaymentService payments;
    @Autowired OrderStockService stock;
    @Autowired vn.teasmart.backend.repository.OrderRepository orderRepository;
    @Autowired vn.teasmart.backend.repository.PaymentRepository paymentRepository;
    @Autowired vn.teasmart.backend.repository.UserRepository userRepository;
    @Autowired CheckoutSessionService checkoutSessions;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final HttpClient http = HttpClient.newHttpClient();
    private String customer, admin, other;
    private long customerId, productId;

    @BeforeEach void isolatedPurchaseFixtures() throws Exception {
        String namespace = UUID.randomUUID().toString();
        String hash = new BCryptPasswordEncoder().encode(TEST_PASSWORD);
        String customerEmail = seedUser("customer", namespace, hash, "CUSTOMER");
        customer = login(customerEmail);
        customerId = jdbc.queryForObject("SELECT user_id FROM users WHERE email=?", Long.class, customerEmail);
        admin = login(seedUser("admin", namespace, hash, "ADMIN"));
        other = login(seedUser("other", namespace, hash, "CUSTOMER"));
        productId = seedProduct(100000, 100, 100);
        deliveredSubjects.clear();
    }

    private String seedUser(String name, String namespace, String hash, String role) {
        String email = name + "-" + namespace + "@example.invalid";
        jdbc.update("INSERT INTO users(full_name,email,password_hash,role,status,created_at,updated_at) VALUES('Fixture',?,?,?,'ACTIVE',NOW(),NOW())",
                email, hash, role);
        return email;
    }

    private long seedProduct(int price, int stock, int grams) {
        String slug = "test-tea-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO products(category_id,region_id,store_id,name,slug,price,stock_quantity,weight_grams,status,created_at,updated_at)
                VALUES(1,1,1,'Test tea',?,?,?,?,'ACTIVE',NOW(),NOW())
                """, slug, price, stock, grams);
        return jdbc.queryForObject("SELECT product_id FROM products WHERE slug=?", Long.class, slug);
    }

    @DynamicPropertySource static void database(DynamicPropertyRegistry r) throws Exception {
        r.add("teasmart.seller.enabled",()->false);
        String server = System.getenv("PAYMENT_TEST_SERVER_URL");
        if (!server.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1):33377/")) throw new IllegalArgumentException("Payment tests require the isolated local MySQL server on port 33377.");
        String url = server + DB;
        try (Connection c = DriverManager.getConnection(server, USER, PASSWORD); Statement s = c.createStatement()) {
            try (ResultSet identity = s.executeQuery("SELECT @@port")) {
                if (!identity.next() || identity.getInt(1) != 33377)
                    throw new IllegalStateException("Refusing to write to an unverified MySQL instance.");
            }
            s.execute("CREATE DATABASE `" + DB + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        try (Connection c = DriverManager.getConnection(url, USER, PASSWORD); Statement s = c.createStatement()) {
            String baseline = Files.readString(Path.of("src/test/resources/payment/baseline-schema.sql"));
            for (String part : baseline.replaceAll("(?m)^--.*$", "").split(";")) {
                String sql = part.replaceAll("(?m)^--.*$", "").trim();
                if (!sql.isEmpty()) { s.execute(sql); var table = java.util.regex.Pattern.compile("CREATE TABLE `?([a-z_]+)").matcher(sql); if (!table.find()) throw new IllegalArgumentException("Unexpected fixture schema statement"); TABLES.add(table.group(1)); }
            }
            String hash = new BCryptPasswordEncoder().encode(TEST_PASSWORD);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO users(full_name,email,password_hash,role,status,created_at,updated_at) VALUES('Legacy','legacy@example.invalid',?,'CUSTOMER','ACTIVE','2001-01-01','2001-01-01')")) {
                ps.setString(1, hash); ps.executeUpdate();
            }
            s.execute("INSERT INTO orders(order_code,user_id,recipient_name,recipient_phone,shipping_address,subtotal,shipping_fee,total_amount,order_status,created_at,updated_at) VALUES('LEGACY-CANCEL',1,'Legacy','0912345678','Legacy',100,0,100,'CANCELLED','2001-01-01','2001-01-01')");
            s.execute("INSERT INTO payments(order_id,payment_method,amount,payment_status,created_at,updated_at) VALUES(1,'COD',100,'PENDING','2001-01-01','2001-01-01')");
            String migration = Files.readString(Path.of("database/migrations/0512_extend_order_payment_metadata.sql"));
            for (String part : migration.replaceAll("(?m)^--.*$", "").split(";")) {
                String sql = part.replaceAll("(?m)^--.*$", "").trim(); if (!sql.isEmpty()) s.execute(sql);
            }
            String identityMigration = Files.readString(Path.of("database/migrations/auth04_add_google_identity.sql"))
                    .replaceAll("(?m)^--.*$", "").replaceAll("(?m)^DELIMITER.*$", "");
            for (String part : identityMigration.split("\\$\\$")) {
                if (!part.isBlank()) s.execute(part.trim());
            }
            s.execute(Files.readString(Path.of("database/migrations/auth05_add_google_email.sql")));
            s.execute("INSERT INTO orders(order_code,user_id,recipient_name,recipient_phone,shipping_address,subtotal,shipping_fee,total_amount,order_status,created_at,updated_at) VALUES('LEGACY-VNPAY',1,'Synthetic','0912345678','Synthetic',100,0,100,'CANCELLED','2001-01-01','2001-01-01')");
            s.execute("INSERT INTO payments(order_id,payment_method,amount,payment_status,transaction_code,created_at,updated_at,gateway,merchant_reference,gateway_response_code,gateway_transaction_status,reconciliation_required) VALUES(2,'ONLINE',100,'FAILED','VNPAY:legacy123','2001-01-01','2001-01-01','VNPAY','TSVNPLEGACY001','51','02',FALSE)");
            String checkoutMigration = Files.readString(Path.of("database/migrations/1027_checkout_sessions_payment_attempts.sql"));
            for (String part : checkoutMigration.replaceAll("(?m)^--.*$", "").split(";")) {
                String sql = part.replaceAll("(?m)^--.*$", "").trim();
                if (!sql.isEmpty()) s.execute(sql);
            }
            TABLES.add("payment_attempts"); TABLES.add("checkout_session_items"); TABLES.add("checkout_sessions");
            if (!START_WITHOUT_AUDIT) {
                s.execute(Files.readString(Path.of("database/migrations/1111_vnpay_checkout_audit.sql")));
                TABLES.add("vnpay_checkout_audit");
            }
            s.executeUpdate("INSERT INTO categories(name,slug,status,created_at,updated_at) VALUES('Test','test','ACTIVE',NOW(),NOW())");
            s.executeUpdate("INSERT INTO tea_regions(name,status,created_at,updated_at) VALUES('Test','ACTIVE',NOW(),NOW())");
            s.executeUpdate("INSERT INTO stores(name,status,created_at,updated_at) VALUES('Test','ACTIVE',NOW(),NOW())");
        }
        byte[] jwt = new byte[32]; new java.security.SecureRandom().nextBytes(jwt);
        r.add("spring.datasource.url", () -> url); r.add("spring.datasource.username", () -> USER); r.add("spring.datasource.password", () -> PASSWORD);
        r.add("teasmart.jwt.secret-base64", () -> Base64.getEncoder().encodeToString(jwt)); r.add("teasmart.jwt.issuer", () -> "payment-tests");
        r.add("teasmart.mail.enabled", () -> true);
        r.add("teasmart.mail.from", () -> "sender@example.invalid");
        r.add("teasmart.google.enabled", () -> false);
        r.add("teasmart.vnpay.enabled", () -> true); r.add("teasmart.vnpay.tmn-code", () -> "TESTONLY");
        r.add("teasmart.vnpay.hash-secret", () -> SECRET); r.add("teasmart.vnpay.return-url", () -> "http://localhost:3000/payment/vnpay-return");
        r.add("teasmart.vnpay.verified-final-failure-pairs", () -> "24:02,51:02");
        r.add("teasmart.vnpay.reconciliation-delay-ms", () -> 86400000);
        r.add("teasmart.vnpay.reconciliation-enabled", () -> false);
        System.out.println("Payment migration test database: " + DB);
    }
    @BeforeAll static void noExternalGateway() { /* All callback inputs are generated locally; no merchant credentials. */ }
    @AfterAll static void cleanup() throws Exception {
        if (Boolean.getBoolean("teasmart.payment-test.retain-fixtures")) {
            System.out.println("Payment test fixtures retained in: " + DB);
            return;
        }
        String server = System.getenv("PAYMENT_TEST_SERVER_URL");
        try (Connection c = DriverManager.getConnection(server + DB, USER, PASSWORD); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM payment_attempts");
            s.executeUpdate("DELETE FROM checkout_session_items");
            s.executeUpdate("DELETE FROM payments");
            s.executeUpdate("DELETE FROM order_items");
            s.executeUpdate("DELETE FROM orders");
            s.executeUpdate("DELETE FROM checkout_sessions");
            var reversed = new ArrayList<>(TABLES); Collections.reverse(reversed);
            for (String table : reversed) s.executeUpdate("DELETE FROM `" + table + "`");
            for (String table : TABLES) try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
                rs.next(); assertEquals(0, rs.getInt(1));
            }
            for (String table : List.of("payment_attempts", "checkout_session_items", "checkout_sessions"))
                try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) { rs.next(); assertEquals(0, rs.getInt(1)); }
        }
        System.out.println("Payment HTTP requests: " + requests + "; test fixtures cleaned; no database dropped or AUTO_INCREMENT reset.");
    }
    private record Result(int status, JsonNode json) { }
    private Result call(String method, String path, String token, Object body, String key) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(java.time.Duration.ofSeconds(20));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (key != null) b.header("Idempotency-Key", key);
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        var response = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        synchronized (PaymentMysqlTest.class) { requests++; }
        return new Result(response.statusCode(), response.body().isBlank() ? null : JSON.readTree(response.body()));
    }
    private String login(String email) throws Exception {
        var r = call("POST", "/api/auth/login", null, Map.of("email", email, "password", TEST_PASSWORD), null);
        assertEquals(200, r.status); return r.json.get("accessToken").asText();
    }
    private Map<String, Object> shipping() { return Map.of("recipientName", "Test Customer", "recipientPhone", "0912345678", "shippingAddress", "Test address"); }
    private long checkout(boolean online, String key) throws Exception {
        assertEquals(200, call("POST", "/api/cart/items", customer, Map.of("productId", productId, "quantity", 2), null).status);
        var r = call("POST", online ? "/api/orders/vnpay" : "/api/orders", customer, shipping(), key);
        assertEquals(201, r.status, "legacy checkout response: " + r.json); var order = online ? r.json.get("order") : r.json;
        if (online) assertTrue(r.json.get("paymentUrl").asText().startsWith("https://sandbox.vnpayment.vn/"));
        return order.get("orderId").asLong();
    }
    private Result ipn(long id, String response, String status, String transaction, String amount) throws Exception {
        String ref = jdbc.queryForObject("SELECT merchant_reference FROM payments WHERE order_id=?", String.class, id);
        var data = new TreeMap<String, String>(); data.put("vnp_TmnCode", "TESTONLY"); data.put("vnp_TxnRef", ref);
        data.put("vnp_Amount", amount); data.put("vnp_ResponseCode", response); data.put("vnp_TransactionStatus", status);
        data.put("vnp_TransactionNo", transaction); data.put("vnp_PayDate", VnpayGateway.DATE.format(PaymentTime.now()));
        String query = VnpayGateway.canonical(data); return call("GET", "/api/payments/vnpay/ipn?" + query + "&vnp_SecureHash=" + gateway.sign(query), null, null, null);
    }
    private Result sessionIpn(String reference, String response, String status, String transaction, String amount) throws Exception {
        var data = new TreeMap<String, String>(); data.put("vnp_TmnCode", "TESTONLY"); data.put("vnp_TxnRef", reference);
        data.put("vnp_Amount", amount); data.put("vnp_ResponseCode", response); data.put("vnp_TransactionStatus", status);
        data.put("vnp_TransactionNo", transaction); data.put("vnp_PayDate", VnpayGateway.DATE.format(PaymentTime.now()));
        String query = VnpayGateway.canonical(data); return call("GET", "/api/payments/vnpay/ipn?" + query + "&vnp_SecureHash=" + gateway.sign(query), null, null, null);
    }
    private Result createBuyNowSession(long quantity, String key) throws Exception {
        return call("POST", "/api/checkout-sessions", customer,
                Map.of("sourceType", "BUY_NOW", "buyNowProductId", productId, "buyNowQuantity", quantity, "shipping", shipping()), key);
    }
    private Result startSessionAttempt(long sessionId, String key) throws Exception {
        return call("POST", "/api/checkout-sessions/" + sessionId + "/payment-attempts/vnpay", customer, null, key);
    }
    private long stock() { return jdbc.queryForObject("SELECT stock_quantity FROM products WHERE product_id=?", Long.class, productId); }

    @Test @Order(1) void migrationPreservesLegacyAndCreatesExactColumns() throws Exception {
        assertEquals(23, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class));
        assertEquals(34, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('orders','payments')", Integer.class)); // 22 baseline + 11 metadata columns + checkout_session_id
        assertNull(jdbc.queryForObject("SELECT stock_released_at FROM orders WHERE order_id=1", Object.class));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT order_status FROM orders WHERE order_id=1", String.class));
        assertEquals(false, jdbc.queryForObject("SELECT reconciliation_required FROM payments WHERE order_id=1", Boolean.class));
        assertEquals("FAILED_FINAL", jdbc.queryForObject("SELECT status FROM payment_attempts WHERE legacy_order_id=2", String.class));
        assertEquals("VNPAY:legacy123", jdbc.queryForObject("SELECT transaction_code FROM payments WHERE order_id=2", String.class));
        assertEquals("2001-01-01 00:00:00", jdbc.queryForObject("SELECT CAST(paid_at AS CHAR) FROM payments WHERE order_id=1", String.class) == null
                ? jdbc.queryForObject("SELECT CAST(created_at AS CHAR) FROM payments WHERE order_id=1", String.class) : "bad");

    }
    @Test @Order(2) void codConfirmationAndReplay() throws Exception {
        String key = UUID.randomUUID().toString(); long before = stock(); long id = checkout(false, key);
        assertEquals(before - 2, stock());
        assertEquals(List.of("TeaSmart — Xác nhận đặt hàng"), deliveredSubjects);
        assertEquals(200, call("POST", "/api/orders", customer, shipping(), key).status);
        assertEquals(1, deliveredSubjects.size()); assertEquals(before - 2, stock());
        assertEquals(409, call("POST", "/api/orders", customer, Map.of("recipientName", "Other", "recipientPhone", "0912345678", "shippingAddress", "Other"), key).status);
        assertEquals(409, call("PATCH", "/api/admin/orders/" + id + "/payment/confirm-cod", admin, null, null).status);
        for (String s : List.of("CONFIRMED", "SHIPPING", "DELIVERED")) assertEquals(200, call("PATCH", "/api/admin/orders/" + id + "/status", admin, Map.of("status", s), null).status);
        assertEquals(200, call("PATCH", "/api/admin/orders/" + id + "/payment/confirm-cod", admin, null, null).status);
        var time = jdbc.queryForObject("SELECT paid_at FROM payments WHERE order_id=?", Timestamp.class, id);
        assertEquals(200, call("PATCH", "/api/admin/orders/" + id + "/payment/confirm-cod", admin, null, null).status);
        assertEquals(time, jdbc.queryForObject("SELECT paid_at FROM payments WHERE order_id=?", Timestamp.class, id));
        assertNotNull(jdbc.queryForObject("SELECT confirmed_by_admin_id FROM payments WHERE order_id=?", Long.class, id));
        assertEquals(4, deliveredSubjects.size()); // One new order + three transitions; COD confirmation/replay emits no VNPay email.

    }
    @Test @Order(3) void codCancelAndLegacyMarkerNeverRestoreTwice() throws Exception {
        long before = stock(), id = checkout(false, UUID.randomUUID().toString());
        assertEquals(200, call("PATCH", "/api/orders/" + id + "/cancel", customer, null, null).status);
        assertEquals(before, stock()); assertNotNull(jdbc.queryForObject("SELECT stock_released_at FROM orders WHERE order_id=?", Timestamp.class, id));
        assertEquals(409, call("PATCH", "/api/orders/" + id + "/cancel", customer, null, null).status); assertEquals(before, stock());
        assertEquals("PENDING", jdbc.queryForObject("SELECT payment_status FROM payments WHERE order_id=?", String.class, id));
    }
    @Test @Order(4) void vnpayDuplicateConcurrentAndFulfillment() throws Exception {
        long before = stock(); Result session = createBuyNowSession(2, UUID.randomUUID().toString());
        assertEquals(201, session.status); long sessionId = session.json.get("checkoutSessionId").asLong();
        Result attempt = startSessionAttempt(sessionId, UUID.randomUUID().toString());
        String ref = attempt.json.get("attemptReference").asText();
        String amount = new java.math.BigDecimal(attempt.json.get("session").get("totalAmount").asText()).movePointRight(2).toBigIntegerExact().toString();
        assertEquals("00", sessionIpn(ref, "00", "00", "1001", amount).json.get("RspCode").asText());
        long id = jdbc.queryForObject("SELECT order_id FROM orders WHERE checkout_session_id=?", Long.class, sessionId);
        long after = stock(); assertEquals(before - 2, after);
        assertEquals(409, call("PATCH", "/api/orders/" + id + "/cancel", customer, null, null).status);
        assertEquals(200, call("PATCH", "/api/admin/orders/" + id + "/status", admin, Map.of("status", "CONFIRMED"), null).status);
        assertEquals("04", sessionIpn(ref, "00", "00", "1001", "1").json.get("RspCode").asText());
        var pool = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<Future<Result>>(); for (int i=0;i<4;i++) futures.add(pool.submit(() -> sessionIpn(ref,"00","00","1001",amount)));
            var codes = new ArrayList<String>(); for (var f : futures) codes.add(f.get(30, TimeUnit.SECONDS).json.get("RspCode").asText());
            assertEquals(4, Collections.frequency(codes,"02"));
        } finally { pool.shutdownNow(); }
        assertEquals(1, Collections.frequency(deliveredSubjects, "TeaSmart — Đã ghi nhận thanh toán"));
        assertEquals(after, stock()); assertEquals("PAID", jdbc.queryForObject("SELECT payment_status FROM payments WHERE order_id=?", String.class,id));
        for (String s : List.of("SHIPPING","DELIVERED")) assertEquals(200, call("PATCH", "/api/admin/orders/"+id+"/status",admin,Map.of("status",s),null).status);
    }
    @Test @Order(5) void failureWithoutVerifiedFinalityKeepsStockAndPaidCannotDowngrade() throws Exception {
        long before=stock(); Result session=createBuyNowSession(1,UUID.randomUUID().toString()); long sessionId=session.json.get("checkoutSessionId").asLong();
        Result attempt=startSessionAttempt(sessionId,UUID.randomUUID().toString()); String ref=attempt.json.get("attemptReference").asText();
        String amount=new java.math.BigDecimal(attempt.json.get("session").get("totalAmount").asText()).movePointRight(2).toBigIntegerExact().toString();
        assertEquals("00",sessionIpn(ref,"00","02","0",amount).json.get("RspCode").asText());
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT status FROM payment_attempts WHERE merchant_reference=?",String.class,ref));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?",Integer.class,sessionId)); assertEquals(before-1,stock());
        assertEquals("00",sessionIpn(ref,"00","00","1002",amount).json.get("RspCode").asText());
        Long orderId=jdbc.queryForObject("SELECT order_id FROM orders WHERE checkout_session_id=?",Long.class,sessionId);
        assertNotNull(orderId); assertEquals("00",sessionIpn(ref,"24","02","0",amount).json.get("RspCode").asText());
        assertEquals("PAID",jdbc.queryForObject("SELECT payment_status FROM payments WHERE order_id=?",String.class,orderId));
        assertEquals(true,jdbc.queryForObject("SELECT reconciliation_required FROM payments WHERE order_id=?",Boolean.class,orderId)); assertEquals(before-1,stock());
    }
    @Test @Order(6) void securityOwnershipAndInvalidInputs() throws Exception {
        assertEquals(401,call("GET","/api/orders/1/payment",null,null,null).status);
        assertEquals(401,call("GET","/api/orders/1/payment","invalid",null,null).status);
        assertEquals(403,call("GET","/api/orders/1/payment",admin,null,null).status);
        assertEquals(403,call("PATCH","/api/admin/orders/1/payment/confirm-cod",customer,null,null).status);
        assertEquals(404,call("GET","/api/orders/1/payment",other,null,null).status);
        assertEquals(400,call("POST","/api/orders/vnpay",customer,shipping(),null).status);
        assertEquals(400,call("PATCH","/api/admin/orders/1/payment/confirm-cod",admin,Map.of("paid",true),null).status);
        var malformed=call("GET","/api/payments/vnpay/ipn?vnp_Amount=1",null,null,null); assertEquals(200,malformed.status); assertEquals("97",malformed.json.get("RspCode").asText());
        for(String path:List.of("/api/products","/api/categories","/api/tea-regions","/api/stores")) assertEquals(200,call("GET",path,null,null,null).status);
        assertEquals(200,call("GET","/api/users/me",customer,null,null).status); assertEquals(200,call("GET","/api/users/me",admin,null,null).status);
        assertEquals(200,call("GET","/api/admin/reviews",admin,null,null).status);
    }
    @Test @Order(7) void verifiedFailureReleaseRollbackOverflowAndLateSuccess() throws Exception {
        long before = stock(); Result created = createBuyNowSession(1, UUID.randomUUID().toString());
        long sessionId = created.json.get("checkoutSessionId").asLong();
        Result attempt = startSessionAttempt(sessionId, UUID.randomUUID().toString());
        String reference = attempt.json.get("attemptReference").asText();
        var notification = new VnpayGateway.Notification(reference, new java.math.BigDecimal("100000"), "51", "02", "0", null);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        int mailsBeforeRollback = deliveredSubjects.size();
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            assertEquals("00", checkoutSessions.acceptAttempt(notification, false).responseCode());
            throw new IllegalStateException("Controlled rollback after flush");
        }));
        assertEquals(mailsBeforeRollback, deliveredSubjects.size());
        assertEquals(before - 1, stock());
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM payment_attempts WHERE merchant_reference=?", String.class, reference));
        assertEquals("00", sessionIpn(reference,"51","02","9201","10000000").json.get("RspCode").asText());
        assertEquals("FAILED_FINAL", jdbc.queryForObject("SELECT status FROM payment_attempts WHERE merchant_reference=?", String.class, reference));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?", Integer.class, sessionId));
        assertEquals("00", sessionIpn(reference,"00","00","9202","10000000").json.get("RspCode").asText());
        Long orderId = jdbc.queryForObject("SELECT order_id FROM orders WHERE checkout_session_id=?", Long.class, sessionId);
        assertNotNull(orderId); assertEquals("PAID", jdbc.queryForObject("SELECT payment_status FROM payments WHERE order_id=?", String.class, orderId));
        assertTrue(jdbc.queryForObject("SELECT reconciliation_required FROM payments WHERE order_id=?", Boolean.class, orderId));
        assertEquals(before - 1, stock());
    }
    @Test @Order(8) void concurrentCheckoutOnlyCreatesOneOrderAndOneStockDeduction() throws Exception {
        long before=stock(); String key=UUID.randomUUID().toString();
        Map<String,Object> body=Map.of("sourceType","BUY_NOW","buyNowProductId",productId,"buyNowQuantity",2,"shipping",shipping());
        Set<Long> ids=new HashSet<>(); var pool=Executors.newFixedThreadPool(3);
        try {
            var futures=new ArrayList<Future<Result>>();
            for(int i=0;i<3;i++) futures.add(pool.submit(() -> call("POST","/api/checkout-sessions",customer,body,key)));
            for(var f:futures) {var result=f.get(30,TimeUnit.SECONDS); assertEquals(201,result.status,String.valueOf(result.json));ids.add(result.json.get("checkoutSessionId").asLong());}
            assertEquals(1,ids.size());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM checkout_sessions WHERE checkout_session_id=?",Integer.class,ids.iterator().next()));
        } finally {pool.shutdownNow();}
        long sessionId=ids.iterator().next(); assertEquals(before-2,stock());
        assertEquals(200,call("POST","/api/checkout-sessions/"+sessionId+"/confirm-cod",customer,null,null).status);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?",Integer.class,sessionId)); assertEquals(before-2,stock());
    }
    @Test @Order(9) void schemaConstraintsAndOfflineReconciliation() throws Exception {
        long before=stock(); Result session=createBuyNowSession(1,UUID.randomUUID().toString()); long sessionId=session.json.get("checkoutSessionId").asLong();
        Result attempt=startSessionAttempt(sessionId,UUID.randomUUID().toString()); String ref=attempt.json.get("attemptReference").asText();
        Long attemptId=jdbc.queryForObject("SELECT payment_attempt_id FROM payment_attempts WHERE merchant_reference=?",Long.class,ref);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE payment_attempts SET merchant_reference=? WHERE payment_attempt_id<>?", "TSVNPLEGACY001", attemptId));
        assertEquals(200,call("GET","/api/checkout-sessions/payment-status?reference="+ref,customer,null,null).status);
        assertEquals(404,call("GET","/api/checkout-sessions/payment-status?reference="+ref,other,null,null).status);
        assertEquals(409,call("POST","/api/checkout-sessions/"+sessionId+"/cancel",customer,null,null).status);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?",Integer.class,sessionId));
        assertEquals(before-1,stock()); assertNull(jdbc.queryForObject("SELECT last_reconciliation_at FROM payment_attempts WHERE payment_attempt_id=?",Timestamp.class,attemptId));
        Result duplicate = createBuyNowSession(1, UUID.randomUUID().toString());
        assertEquals(409, duplicate.status);
        assertEquals("PRODUCT_PAYMENT_STATUS_UNRESOLVED", duplicate.json.path("code").asText());
    }

    @Test @Order(10) void welcomeAndSmtpFailureDoNotRollbackCommittedOrder() throws Exception {
        assertEquals(201, call("POST", "/api/auth/register", null, Map.of("fullName", "Email Fixture",
                "email", "notification-" + UUID.randomUUID() + "@example.invalid", "phone", "0987654321",
                "password", TEST_PASSWORD), null).status);
        assertEquals(List.of("Chào mừng đến với TeaSmart"), deliveredSubjects);
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("Controlled SMTP failure"))
                .when(mailSender).send(org.mockito.ArgumentMatchers.any(jakarta.mail.internet.MimeMessage.class));
        long id = checkout(false, UUID.randomUUID().toString());
        assertEquals("PENDING", jdbc.queryForObject("SELECT order_status FROM orders WHERE order_id=?", String.class, id));
        assertEquals("PENDING", jdbc.queryForObject("SELECT payment_status FROM payments WHERE order_id=?", String.class, id));
        Result session=createBuyNowSession(1,UUID.randomUUID().toString()); long sessionId=session.json.get("checkoutSessionId").asLong();
        Result attempt=startSessionAttempt(sessionId,UUID.randomUUID().toString()); String ref=attempt.json.get("attemptReference").asText();
        String amount=new java.math.BigDecimal(attempt.json.get("session").get("totalAmount").asText()).movePointRight(2).toBigIntegerExact().toString();
        assertEquals("00",sessionIpn(ref,"00","00","9001",amount).json.get("RspCode").asText());
        Long orderId=jdbc.queryForObject("SELECT order_id FROM orders WHERE checkout_session_id=?",Long.class,sessionId);
        assertNotNull(orderId); assertEquals("PAID",jdbc.queryForObject("SELECT payment_status FROM payments WHERE order_id=?",String.class,orderId));
    }

    @Test @Order(11) void checkoutSessionCodIsIdempotentAndShippingEditIsOwnerScoped() throws Exception {
        long before = stock(); String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("sourceType", "BUY_NOW", "buyNowProductId", productId, "buyNowQuantity", 2, "shipping", shipping());
        Result created = call("POST", "/api/checkout-sessions", customer, body, key);
        assertEquals(201, created.status); long sessionId = created.json.get("checkoutSessionId").asLong();
        assertTrue(created.json.get("orderId").isNull()); assertEquals("READY", created.json.get("status").asText());
        assertEquals(before - 2, stock());
        Result replay = call("POST", "/api/checkout-sessions", customer, body, key);
        assertEquals(sessionId, replay.json.get("checkoutSessionId").asLong()); assertEquals(before - 2, stock());

        Result placed = call("POST", "/api/checkout-sessions/" + sessionId + "/confirm-cod", customer, null, null);
        assertEquals(200, placed.status); assertEquals("COMPLETED", placed.json.get("status").asText());
        long orderId = placed.json.get("orderId").asLong();
        Result repeated = call("POST", "/api/checkout-sessions/" + sessionId + "/confirm-cod", customer, null, null);
        assertEquals(orderId, repeated.json.get("orderId").asLong());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?", Integer.class, sessionId));
        assertEquals(before - 2, stock());

        Map<String, String> changed = Map.of("recipientName", "Updated Customer", "recipientPhone", "0987654321", "shippingAddress", "Updated test address");
        assertEquals(200, call("PATCH", "/api/orders/" + orderId + "/shipping-info", customer, changed, null).status);
        assertEquals(404, call("PATCH", "/api/orders/" + orderId + "/shipping-info", other, changed, null).status);
        assertEquals("Updated test address", jdbc.queryForObject("SELECT shipping_address FROM orders WHERE order_id=?", String.class, orderId));
    }

    @Test @Order(12) void checkoutSessionCancelReleasesReservationOnce() throws Exception {
        long before = stock();
        Result created = call("POST", "/api/checkout-sessions", customer,
                Map.of("sourceType", "BUY_NOW", "buyNowProductId", productId, "buyNowQuantity", 1, "shipping", shipping()), UUID.randomUUID().toString());
        assertEquals(201, created.status); long id = created.json.get("checkoutSessionId").asLong();
        assertEquals(before - 1, stock());
        Result cancelled = call("POST", "/api/checkout-sessions/" + id + "/cancel", customer, null, null);
        assertEquals(200, cancelled.status); assertEquals("CANCELLED", cancelled.json.get("status").asText());
        assertEquals(before, stock());
        assertEquals("CANCELLED", call("POST", "/api/checkout-sessions/" + id + "/cancel", customer, null, null).json.get("status").asText());
        assertEquals(before, stock()); assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?", Integer.class, id));
    }

    @Test @Order(13) void checkoutVnpayFailureCreatesNoOrderAndVerifiedSuccessCreatesOne() throws Exception {
        long before = stock();
        Result created = call("POST", "/api/checkout-sessions", customer,
                Map.of("sourceType", "BUY_NOW", "buyNowProductId", productId, "buyNowQuantity", 1, "shipping", shipping()), UUID.randomUUID().toString());
        assertEquals(201, created.status); long sessionId = created.json.get("checkoutSessionId").asLong();
        assertEquals(before - 1, stock()); assertTrue(created.json.get("orderId").isNull());
        Result first = call("POST", "/api/checkout-sessions/" + sessionId + "/payment-attempts/vnpay", customer, null, UUID.randomUUID().toString());
        assertEquals(200, first.status); assertTrue(first.json.get("paymentUrl").asText().startsWith("https://sandbox.vnpayment.vn/"));
        assertTrue(first.json.get("session").get("orderId").isNull());
        String reference = first.json.get("attemptReference").asText();
        String amount = new java.math.BigDecimal(first.json.get("session").get("totalAmount").asText()).movePointRight(2).toBigIntegerExact().toString();
        assertEquals(409, call("POST", "/api/checkout-sessions/" + sessionId + "/cancel", customer, null, null).status);
        assertEquals("00", sessionIpn(reference, "51", "02", "9101", amount).json.get("RspCode").asText());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?", Integer.class, sessionId));
        assertEquals("FAILED_FINAL", jdbc.queryForObject("SELECT status FROM payment_attempts WHERE merchant_reference=?", String.class, reference));
        assertEquals(before - 1, stock());

        Result retry = call("POST", "/api/checkout-sessions/" + sessionId + "/payment-attempts/vnpay", customer, null, UUID.randomUUID().toString());
        assertEquals(200, retry.status); String successfulReference = retry.json.get("attemptReference").asText();
        assertEquals("00", sessionIpn(successfulReference, "00", "00", "9102", amount).json.get("RspCode").asText());
        Long orderId = jdbc.queryForObject("SELECT order_id FROM orders WHERE checkout_session_id=?", Long.class, sessionId);
        assertNotNull(orderId); assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?", Integer.class, sessionId));
        assertEquals("PAID", jdbc.queryForObject("SELECT payment_status FROM payments WHERE order_id=?", String.class, orderId));
        assertEquals(before - 1, stock());
        assertEquals("02", sessionIpn(successfulReference, "00", "00", "9102", amount).json.get("RspCode").asText());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?", Integer.class, sessionId));
        assertEquals(before - 1, stock());
    }

    @Test @Order(17) void vnpayAuditDistinguishesReturnIpnAndQueryDr() throws Exception {
        long before = stock();
        long previousAuditId = jdbc.queryForObject("SELECT COALESCE(MAX(audit_id), 0) FROM vnpay_checkout_audit", Long.class);
        Result created = createBuyNowSession(1, UUID.randomUUID().toString());
        assertEquals(201, created.status);
        long sessionId = created.json.path("checkoutSessionId").asLong();
        Result started = call("POST", "/api/checkout-sessions/" + sessionId + "/payment-attempts/vnpay", customer, null, UUID.randomUUID().toString());
        assertEquals(200, started.status);
        String ref = started.json.path("attemptReference").asText();
        var amount = created.json.path("totalAmount").decimalValue();
        String cents = VnpayGateway.amount(amount);
        Map<String, String> payload = new TreeMap<>(Map.of("vnp_TmnCode", "TESTONLY", "vnp_TxnRef", ref,
                "vnp_Amount", cents, "vnp_ResponseCode", "00", "vnp_TransactionStatus", "02", "vnp_TransactionNo", "0"));
        payload.put("vnp_SecureHash", gateway.sign(VnpayGateway.canonical(payload)));
        assertEquals(200, call("POST", "/api/payments/vnpay/return", customer, payload, null).status);
        assertEquals("04", sessionIpn(ref, "00", "02", "0", "1").json.path("RspCode").asText());
        assertEquals("00", sessionIpn(ref, "00", "02", "0", cents).json.path("RspCode").asText());
        payload.put("vnp_SecureHash", "0".repeat(128));
        String invalidIpn = VnpayGateway.canonical(payload);
        assertEquals("97", call("GET", "/api/payments/vnpay/ipn?" + invalidIpn, null, null, null).json.path("RspCode").asText());

        var timeout = new VnpayQueryClient.QueryResult("TIMEOUT", true, null, 123, null, null);
        var finalResult = new VnpayQueryClient.QueryResult("VERIFIED_RESPONSE", true, 200, 10, "00", "02",
                new VnpayGateway.Notification(ref, amount, "00", "02", "0", null));
        org.mockito.Mockito.doReturn(timeout, finalResult).when(queryClient).queryAttemptDiagnostic(
                org.mockito.ArgumentMatchers.argThat(snapshot -> snapshot != null && ref.equals(snapshot.reference())));
        String adminPath = "/api/admin/checkout-sessions/" + sessionId + "/reconcile";
        assertEquals("UNRESOLVED_RETAINED", call("POST", adminPath, admin, null, null).json.path("outcome").asText());
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT status FROM payment_attempts WHERE merchant_reference=?", String.class, ref));
        assertEquals("VERIFIED_FINAL_FAILURE", call("POST", adminPath, admin, null, null).json.path("outcome").asText());
        assertEquals("FAILED_FINAL", jdbc.queryForObject("SELECT status FROM payment_attempts WHERE merchant_reference=?", String.class, ref));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?", Integer.class, sessionId));
        assertEquals(before, stock()); // Verified final QueryDR outcome released the reservation.
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM checkout_sessions WHERE checkout_session_id=?", String.class, sessionId));
        assertEquals("RELEASED", jdbc.queryForObject("SELECT reservation_status FROM checkout_sessions WHERE checkout_session_id=?", String.class, sessionId));

        assertEquals(List.of(
                "RETURN:SIGNATURE_AND_MERCHANT_VERIFIED:PROCESSING_CODE_00:00:02",
                "IPN:SIGNATURE_AND_MERCHANT_VERIFIED:PROCESSING_CODE_04:00:02",
                "IPN:SIGNATURE_AND_MERCHANT_VERIFIED:PROCESSING_CODE_00:00:02",
                "QUERYDR:TIMEOUT:UNRESOLVED_RETAINED:-:-",
                "QUERYDR:VERIFIED_RESPONSE:VERIFIED_FINAL_FAILURE:00:02"),
                jdbc.queryForList("""
                    SELECT CONCAT(source, ':', verification_outcome, ':', processing_outcome, ':',
                                  COALESCE(gateway_response_code, '-'), ':', COALESCE(gateway_transaction_status, '-'))
                    FROM vnpay_checkout_audit
                    WHERE checkout_session_id=? AND payment_attempt_id IS NOT NULL AND merchant_reference=?
                    ORDER BY audit_id
                    """, String.class, sessionId, ref));
        assertEquals(1, jdbc.queryForObject("""
                SELECT COUNT(*) FROM vnpay_checkout_audit
                WHERE source='IPN' AND verification_outcome='SIGNATURE_REJECTED'
                  AND processing_outcome='REJECTED_CODE_97' AND merchant_reference IS NULL AND audit_id > ?
                """, Integer.class, previousAuditId));
        assertEquals(123L, jdbc.queryForObject("""
                SELECT elapsed_ms FROM vnpay_checkout_audit WHERE merchant_reference=?
                  AND source='QUERYDR' AND verification_outcome='TIMEOUT' AND request_sent=TRUE AND http_status IS NULL
                """, Long.class, ref));
    }

    @Test @Order(14) void buyAgainUsesCurrentProductsWithoutChangingCartOrCreatingOrder() throws Exception {
        long secondProductId = seedProduct(250000, 30, 200);
        List<Map<String, Object>> cartBefore = jdbc.queryForList("SELECT ci.product_id,ci.quantity FROM cart_items ci JOIN carts c ON c.cart_id=ci.cart_id WHERE c.user_id=? ORDER BY ci.product_id", customerId);
        long beforeFirst = stock();
        Result created = call("POST", "/api/checkout-sessions", customer,
                Map.of("sourceType", "BUY_AGAIN", "buyAgainItems", List.of(
                        Map.of("productId", productId, "quantity", 2), Map.of("productId", secondProductId, "quantity", 1)),
                        "shipping", shipping()), UUID.randomUUID().toString());
        assertEquals(201, created.status);
        long sessionId = created.json.get("checkoutSessionId").asLong();
        assertTrue(created.json.get("orderId").isNull());
        assertEquals(beforeFirst - 2, stock());
        assertEquals(29L, jdbc.queryForObject("SELECT stock_quantity FROM products WHERE product_id=?", Long.class, secondProductId));
        assertEquals(cartBefore, jdbc.queryForList("SELECT ci.product_id,ci.quantity FROM cart_items ci JOIN carts c ON c.cart_id=ci.cart_id WHERE c.user_id=? ORDER BY ci.product_id", customerId));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM checkout_session_items WHERE checkout_session_id=? AND source_cart_item_id IS NULL", Integer.class, sessionId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?", Integer.class, sessionId));

        assertEquals(200, call("POST", "/api/checkout-sessions/" + sessionId + "/cancel", customer, null, null).status);
        assertEquals(beforeFirst, stock());
        assertEquals(30L, jdbc.queryForObject("SELECT stock_quantity FROM products WHERE product_id=?", Long.class, secondProductId));
        assertEquals(cartBefore, jdbc.queryForList("SELECT ci.product_id,ci.quantity FROM cart_items ci JOIN carts c ON c.cart_id=ci.cart_id WHERE c.user_id=? ORDER BY ci.product_id", customerId));
    }

    @Test @Order(15) void unknownIsOwnerScopedAndDoesNotBlockOtherCustomer() throws Exception {
        Result a = createBuyNowSession(1, UUID.randomUUID().toString());
        long aid = a.json.get("checkoutSessionId").asLong();
        Result attempt = startSessionAttempt(aid, UUID.randomUUID().toString());
        String ref = attempt.json.get("attemptReference").asText();
        String amount = new java.math.BigDecimal(a.json.get("totalAmount").asText()).movePointRight(2).toBigIntegerExact().toString();
        assertEquals("00", sessionIpn(ref, "00", "02", "0", amount).json.get("RspCode").asText());
        assertEquals("UNKNOWN", call("GET", "/api/checkout-sessions/" + aid, customer, null, null).json.get("latestAttemptStatus").asText());
        assertEquals(404, call("GET", "/api/checkout-sessions/" + aid, other, null, null).status);
        assertEquals(404, call("GET", "/api/checkout-sessions/payment-status?reference=" + ref, other, null, null).status);
        assertEquals(404, call("POST", "/api/checkout-sessions/" + aid + "/refresh", other, null, null).status);
        for (JsonNode row : call("GET", "/api/checkout-sessions/active", other, null, null).json)
            assertNotEquals(aid, row.get("checkoutSessionId").asLong());
        String key = UUID.randomUUID().toString();
        var body = Map.of("sourceType", "BUY_NOW", "buyNowProductId", productId, "buyNowQuantity", 1, "shipping", shipping());
        Result b = call("POST", "/api/checkout-sessions", other, body, key);
        assertEquals(201, b.status); long bid = b.json.get("checkoutSessionId").asLong(); assertNotEquals(aid, bid);
        assertEquals(bid, call("POST", "/api/checkout-sessions", other, body, key).json.get("checkoutSessionId").asLong());
        assertEquals(404, call("GET", "/api/checkout-sessions/" + bid, customer, null, null).status);
        assertEquals(200, call("POST", "/api/checkout-sessions/" + bid + "/confirm-cod", other, null, null).status);
        assertEquals(200, call("POST", "/api/checkout-sessions/" + bid + "/confirm-cod", other, null, null).status);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?", Integer.class, bid));
        Result preserved = call("GET", "/api/checkout-sessions/" + aid, customer, null, null);
        assertEquals("UNKNOWN", preserved.json.get("latestAttemptStatus").asText());
        assertEquals("HELD", preserved.json.get("reservationStatus").asText());
        assertTrue(preserved.json.get("orderId").isNull());
        assertEquals("00", sessionIpn(ref, "00", "00", "9201", amount).json.get("RspCode").asText());
        assertEquals("02", sessionIpn(ref, "00", "00", "9201", amount).json.get("RspCode").asText());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?", Integer.class, aid));
    }

    @Test @Order(16) void sameOwnerSeparatePurchaseDoesNotResolveUnknown() throws Exception {
        Result a = createBuyNowSession(1, UUID.randomUUID().toString());
        long aid = a.json.get("checkoutSessionId").asLong();
        Result attempt = startSessionAttempt(aid, UUID.randomUUID().toString());
        String ref = attempt.json.get("attemptReference").asText();
        String amount = new java.math.BigDecimal(a.json.get("totalAmount").asText()).movePointRight(2).toBigIntegerExact().toString();
        assertEquals("00", sessionIpn(ref, "00", "02", "0", amount).json.get("RspCode").asText());
        assertEquals(409, call("POST", "/api/checkout-sessions/" + aid + "/confirm-cod", customer, null, null).status);
        assertEquals(409, call("POST", "/api/checkout-sessions/" + aid + "/cancel", customer, null, null).status);
        assertEquals(409, startSessionAttempt(aid, UUID.randomUUID().toString()).status);
        var repeatBody = Map.of("sourceType", "BUY_NOW", "buyNowProductId", productId, "buyNowQuantity", 1, "shipping", shipping());
        assertEquals(409, call("POST", "/api/checkout-sessions", customer, repeatBody, UUID.randomUUID().toString()).status);
        long otherProduct = seedProduct(250000, 30, 200);
        var body = Map.of("sourceType", "BUY_NOW", "buyNowProductId", otherProduct, "buyNowQuantity", 1, "shipping", shipping());
        String key = UUID.randomUUID().toString();
        Result next = call("POST", "/api/checkout-sessions", customer, body, key);
        assertEquals(201, next.status); long nextId = next.json.get("checkoutSessionId").asLong(); assertNotEquals(aid, nextId);
        assertEquals(nextId, call("POST", "/api/checkout-sessions", customer, body, key).json.get("checkoutSessionId").asLong());
        Result unchanged = call("GET", "/api/checkout-sessions/" + aid, customer, null, null);
        assertEquals("UNKNOWN", unchanged.json.get("latestAttemptStatus").asText());
        assertEquals("HELD", unchanged.json.get("reservationStatus").asText());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id IN (?,?)", Integer.class, aid, nextId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts WHERE checkout_session_id=?", Integer.class, aid));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts WHERE checkout_session_id=?", Integer.class, nextId));
    }


    @Test @Order(18) void paymentStillProcessesWithoutAuditMigration() throws Exception {
        Result created = createBuyNowSession(1, UUID.randomUUID().toString());
        assertEquals(201, created.status);
        long sessionId = created.json.path("checkoutSessionId").asLong();
        Result started = startSessionAttempt(sessionId, UUID.randomUUID().toString());
        assertEquals(200, started.status);
        String ref = started.json.path("attemptReference").asText();
        String amount = VnpayGateway.amount(created.json.path("totalAmount").decimalValue());

        if (!START_WITHOUT_AUDIT)
            jdbc.execute("RENAME TABLE vnpay_checkout_audit TO vnpay_checkout_audit_fixture_backup");
        try {
            assertEquals("00", sessionIpn(ref, "00", "00", "9301", amount).json.path("RspCode").asText());
            assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM payment_attempts WHERE merchant_reference=?", String.class, ref));
            assertEquals("CONSUMED", jdbc.queryForObject("SELECT reservation_status FROM checkout_sessions WHERE checkout_session_id=?", String.class, sessionId));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?", Integer.class, sessionId));
            assertEquals(99, stock());
        } finally {
            if (!START_WITHOUT_AUDIT)
                jdbc.execute("RENAME TABLE vnpay_checkout_audit_fixture_backup TO vnpay_checkout_audit");
        }

        jdbc.execute(Files.readString(Path.of("database/migrations/1111_vnpay_checkout_audit.sql")));
        if (START_WITHOUT_AUDIT) TABLES.add("vnpay_checkout_audit");
        assertEquals("02", sessionIpn(ref, "00", "00", "9301", amount).json.path("RspCode").asText());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM vnpay_checkout_audit WHERE merchant_reference=? AND source='IPN'", Integer.class, ref));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?", Integer.class, sessionId));
        assertEquals(99, stock());
    }

    @Test @Order(19) void finalFailureUnlocksRepurchaseForBuyNowAndCart() throws Exception {
        for (String source : List.of("BUY_NOW", "CART")) {
            productId = seedProduct(1290000, 8, 500);
            long before = stock();
            assertEquals(200, call("POST", "/api/cart/items", customer,
                    Map.of("productId", productId, "quantity", 1), null).status);
            Map<String, Object> body = source.equals("BUY_NOW")
                    ? Map.of("sourceType", source, "buyNowProductId", productId, "buyNowQuantity", 1, "shipping", shipping())
                    : Map.of("sourceType", source, "shipping", shipping());
            String originalKey = UUID.randomUUID().toString();
            Result original = call("POST", "/api/checkout-sessions", customer, body, originalKey);
            assertEquals(201, original.status, String.valueOf(original.json));
            long oldId = original.json.path("checkoutSessionId").asLong();
            String attemptKey = UUID.randomUUID().toString();
            Result pending = startSessionAttempt(oldId, attemptKey);
            assertEquals(200, pending.status);
            String ref = pending.json.path("attemptReference").asText();
            String cents = VnpayGateway.amount(original.json.path("totalAmount").decimalValue());
            assertEquals("00", sessionIpn(ref, "00", "02", "0", cents).json.path("RspCode").asText());
            assertEquals("UNKNOWN", jdbc.queryForObject("SELECT status FROM payment_attempts WHERE merchant_reference=?", String.class, ref));
            assertEquals("HELD", jdbc.queryForObject("SELECT reservation_status FROM checkout_sessions WHERE checkout_session_id=?", String.class, oldId));
            assertEquals(before - 1, stock());
            Result blocked = call("POST", "/api/checkout-sessions", customer, body, UUID.randomUUID().toString());
            assertEquals(409, blocked.status);
            assertEquals("PRODUCT_PAYMENT_STATUS_UNRESOLVED", blocked.json.path("code").asText());
            if (source.equals("BUY_NOW")) {
                var confirmedBody = new java.util.HashMap<String, Object>(body);
                confirmedBody.put("independentPurchaseIntentId", UUID.randomUUID().toString());
                Result independent = call("POST", "/api/checkout-sessions", customer, confirmedBody, UUID.randomUUID().toString());
                assertEquals(201, independent.status, String.valueOf(independent.json));
                long independentId = independent.json.path("checkoutSessionId").asLong();
                assertNotEquals(oldId, independentId);
                assertEquals("UNKNOWN", jdbc.queryForObject("SELECT status FROM payment_attempts WHERE merchant_reference=?", String.class, ref));
                assertEquals("HELD", jdbc.queryForObject("SELECT reservation_status FROM checkout_sessions WHERE checkout_session_id=?", String.class, oldId));
                assertEquals(200, call("POST", "/api/checkout-sessions/" + independentId + "/cancel", customer, null, null).status);
                var invalidScope = new java.util.HashMap<String, Object>(body);
                invalidScope.put("independentPurchaseIntentId", UUID.randomUUID().toString());
                invalidScope.put("sourceType", "CART");
                assertEquals(400, call("POST", "/api/checkout-sessions", customer, invalidScope, UUID.randomUUID().toString()).status);
            }
            assertEquals(oldId, call("POST", "/api/checkout-sessions", customer, body, originalKey).json.path("checkoutSessionId").asLong());
            assertEquals(409, call("POST", "/api/checkout-sessions/" + oldId + "/confirm-cod", customer, null, null).status);
            assertEquals(409, call("POST", "/api/checkout-sessions/" + oldId + "/cancel", customer, null, null).status);
            // Exercise both expired and still-live sessions. Only this isolated test database is modified.
            if (source.equals("BUY_NOW"))
                jdbc.update("UPDATE checkout_sessions SET expires_at='2001-01-01', reservation_expires_at='2001-01-01' WHERE checkout_session_id=?", oldId);
            assertEquals("00", sessionIpn(ref, "51", "02", "0", cents).json.path("RspCode").asText());
            assertEquals("FAILED_FINAL", jdbc.queryForObject("SELECT status FROM payment_attempts WHERE merchant_reference=?", String.class, ref));
            if (source.equals("CART")) {
                assertEquals("READY", jdbc.queryForObject("SELECT status FROM checkout_sessions WHERE checkout_session_id=?", String.class, oldId));
                assertEquals(200, call("POST", "/api/checkout-sessions/" + oldId + "/cancel", customer, null, null).status);
            }
            assertEquals("RELEASED", jdbc.queryForObject("SELECT reservation_status FROM checkout_sessions WHERE checkout_session_id=?", String.class, oldId));
            assertEquals(before, stock());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?", Integer.class, oldId));
            assertEquals("00", sessionIpn(ref, "51", "02", "0", cents).json.path("RspCode").asText());
            assertEquals(before, stock()); // Replayed failure must not release twice.
            String codKey = UUID.randomUUID().toString();
            Result cod = call("POST", "/api/checkout-sessions", customer, body, codKey);
            assertEquals(201, cod.status, String.valueOf(cod.json));
            long codId = cod.json.path("checkoutSessionId").asLong();
            assertNotEquals(oldId, codId);
            assertEquals(codId, call("POST", "/api/checkout-sessions", customer, body, codKey).json.path("checkoutSessionId").asLong());
            Result placed = call("POST", "/api/checkout-sessions/" + codId + "/confirm-cod", customer, null, null);
            assertEquals(200, placed.status);
            long codOrder = placed.json.path("orderId").asLong();
            assertTrue(codOrder > 0);
            assertEquals(codOrder, call("POST", "/api/checkout-sessions/" + codId + "/confirm-cod", customer, null, null).json.path("orderId").asLong());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?", Integer.class, codId));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts WHERE checkout_session_id=?", Integer.class, codId));
            assertEquals("CONSUMED", jdbc.queryForObject("SELECT reservation_status FROM checkout_sessions WHERE checkout_session_id=?", String.class, codId));
            assertEquals(before - 1, stock());
            // CART checkout consumes its cart row; re-add through the real endpoint for a separate purchase.
            if (source.equals("CART"))
                assertEquals(200, call("POST", "/api/cart/items", customer, Map.of("productId", productId, "quantity", 1), null).status);
            Result online = call("POST", "/api/checkout-sessions", customer, body, UUID.randomUUID().toString());
            assertEquals(201, online.status, String.valueOf(online.json));
            long onlineId = online.json.path("checkoutSessionId").asLong();
            String onlineKey = UUID.randomUUID().toString();
            Result started = startSessionAttempt(onlineId, onlineKey);
            assertEquals(200, started.status);
            String onlineRef = started.json.path("attemptReference").asText();
            assertEquals(onlineRef, startSessionAttempt(onlineId, onlineKey).json.path("attemptReference").asText());
            String onlineCents = VnpayGateway.amount(online.json.path("totalAmount").decimalValue());
            if (source.equals("CART")) {
                // Reusing the BUY_NOW gateway transaction for a different attempt must roll back.
                assertEquals("99", sessionIpn(onlineRef, "00", "00", "9401", onlineCents).json.path("RspCode").asText());
                assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM payment_attempts WHERE merchant_reference=?", String.class, onlineRef));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?", Integer.class, onlineId));
            }
            String transactionNo = source.equals("BUY_NOW") ? "9401" : "9402";
            Result paid = sessionIpn(onlineRef, "00", "00", transactionNo, onlineCents);
            assertEquals("00", paid.json.path("RspCode").asText(), "source=" + source + " now=" + PaymentTime.now() + " attempt=" + jdbc.queryForList("SELECT created_at,status FROM payment_attempts WHERE merchant_reference=?", onlineRef) + " audit=" + jdbc.queryForList("SELECT verification_outcome,processing_outcome FROM vnpay_checkout_audit WHERE merchant_reference=?", onlineRef));
            assertEquals("02", sessionIpn(onlineRef, "00", "00", transactionNo, onlineCents).json.path("RspCode").asText());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?", Integer.class, onlineId));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts WHERE checkout_session_id=?", Integer.class, onlineId));
            assertEquals("PAID", jdbc.queryForObject("SELECT p.payment_status FROM payments p JOIN orders o ON o.order_id=p.order_id WHERE o.checkout_session_id=?", String.class, onlineId));
            assertEquals(before - 2, stock());
            System.out.println("REPURCHASE VERIFIED source=" + source + " failedSession=" + oldId + " codOrder=" + codOrder
                    + " onlineSession=" + onlineId + " stock=" + stock());
            // The BUY_NOW branch must leave its original cart entry intact.
            if (source.equals("BUY_NOW")) {
                assertEquals(1, jdbc.queryForObject("SELECT ci.quantity FROM cart_items ci JOIN carts c ON c.cart_id=ci.cart_id WHERE c.user_id=? AND ci.product_id=?", Integer.class, customerId, productId));
                jdbc.update("DELETE ci FROM cart_items ci JOIN carts c ON c.cart_id=ci.cart_id WHERE c.user_id=?", customerId);
            }
        }
    }

    @Test @Order(20) void confirmedIndependentVnpayRedirectPreservesUnknownAndReplaysSafely() throws Exception {
        Result original = createBuyNowSession(1, UUID.randomUUID().toString());
        long oldId = original.json.path("checkoutSessionId").asLong();
        Result oldAttempt = startSessionAttempt(oldId, UUID.randomUUID().toString());
        String oldRef = oldAttempt.json.path("attemptReference").asText();
        String cents = VnpayGateway.amount(original.json.path("totalAmount").decimalValue());
        assertEquals("00", sessionIpn(oldRef, "00", "02", "0", cents).json.path("RspCode").asText());
        Map<String,Object> body = new HashMap<>(Map.of("sourceType","BUY_NOW", "buyNowProductId",productId,
                "buyNowQuantity",1,"shipping",shipping()));
        assertEquals(409, call("POST", "/api/checkout-sessions", customer, body, UUID.randomUUID().toString()).status);
        body.put("independentPurchaseIntentId", UUID.randomUUID().toString());
        String key = UUID.randomUUID().toString();
        Result fresh = call("POST", "/api/checkout-sessions", customer, body, key);
        assertEquals(201, fresh.status);
        long id = fresh.json.path("checkoutSessionId").asLong();
        assertNotEquals(oldId,id);
        assertEquals(id,call("POST","/api/checkout-sessions",customer,body,key).json.path("checkoutSessionId").asLong());
        String attemptKey=UUID.randomUUID().toString();
        Result attempt=startSessionAttempt(id,attemptKey);
        assertEquals(200,attempt.status);
        assertTrue(attempt.json.path("paymentUrl").asText().startsWith("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html?"));
        String ref=attempt.json.path("attemptReference").asText();
        assertNotEquals(oldRef,ref);
        var pool=Executors.newFixedThreadPool(3);
        try {
            var jobs=new ArrayList<Future<Result>>();
            for(int n=0;n<3;n++) jobs.add(pool.submit(()->startSessionAttempt(id,attemptKey)));
            for(var job:jobs) assertEquals(ref,job.get().json.path("attemptReference").asText());
        } finally { pool.shutdownNow(); }
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts WHERE checkout_session_id=?",Integer.class,id));
        assertEquals("00",sessionIpn(ref,"00","00","9501",cents).json.path("RspCode").asText());
        assertEquals("02",sessionIpn(ref,"00","00","9501",cents).json.path("RspCode").asText());
        long orderId=call("GET","/api/checkout-sessions/"+id,customer,null,null).json.path("orderId").asLong();
        assertEquals("PAID",jdbc.queryForObject("SELECT payment_status FROM payments WHERE order_id=?",String.class,orderId));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE order_id=?",Integer.class,orderId));
        assertEquals("UNKNOWN",jdbc.queryForObject("SELECT status FROM payment_attempts WHERE merchant_reference=?",String.class,oldRef));
        assertEquals("HELD",jdbc.queryForObject("SELECT reservation_status FROM checkout_sessions WHERE checkout_session_id=?",String.class,oldId));
    }

    @Test @Order(22) void cancelledReleasedSessionNeverFinalizesLateChargeOrTouchesNewPurchase() throws Exception {
        for (String method : List.of("COD", "VNPAY")) {
            productId = seedProduct(100000, 4, 100);
            long before = stock();
            Result original = createBuyNowSession(1, UUID.randomUUID().toString());
            long oldId = original.json.path("checkoutSessionId").asLong();
            String ref = startSessionAttempt(oldId, UUID.randomUUID().toString()).json.path("attemptReference").asText();
            String cents = VnpayGateway.amount(original.json.path("totalAmount").decimalValue());
            assertEquals("00", sessionIpn(ref, "24", "02", "0", cents).json.path("RspCode").asText());
            long newId = createBuyNowSession(1, UUID.randomUUID().toString()).json.path("checkoutSessionId").asLong();
            if (method.equals("COD")) assertEquals(200, call("POST", "/api/checkout-sessions/" + newId + "/confirm-cod", customer, null, null).status);
            else assertEquals(200, startSessionAttempt(newId, UUID.randomUUID().toString()).status);
            var pool = Executors.newFixedThreadPool(3);
            try {
                var jobs = new ArrayList<Future<Result>>();
                for (int i = 0; i < 3; i++) jobs.add(pool.submit(() -> sessionIpn(ref, "00", "00", Long.toString(980000 + oldId), cents)));
                for (var job : jobs) { Result response = job.get(); assertTrue(List.of("00", "02").contains(response.json.path("RspCode").asText()), response.json.toString()); }
            } finally { pool.shutdownNow(); }
            assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM payment_attempts WHERE merchant_reference=?", String.class, ref));
            assertTrue(jdbc.queryForObject("SELECT reconciliation_required FROM payment_attempts WHERE merchant_reference=?", Boolean.class, ref));
            assertEquals("RECONCILIATION_REQUIRED", jdbc.queryForObject("SELECT status FROM checkout_sessions WHERE checkout_session_id=?", String.class, oldId));
            assertEquals("RELEASED", jdbc.queryForObject("SELECT reservation_status FROM checkout_sessions WHERE checkout_session_id=?", String.class, oldId));
            assertThrows(vn.teasmart.backend.exception.PaymentException.class, () -> checkoutSessions.finalizePaidSession(oldId));
            assertEquals("00", sessionIpn(ref, "24", "02", "0", cents).json.path("RspCode").asText());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?", Integer.class, oldId));
            assertEquals(method.equals("COD") ? 1 : 0, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?", Integer.class, newId));
            assertEquals(before - 1, stock());
        }
    }

    @Test @Order(21) void verifiedReturnCancellationReleasesOnceAndAllowsFreshCodOrVnpaySession() throws Exception {
        for (String nextMethod : List.of("COD", "VNPAY")) {
            productId = seedProduct(215000, 6, 250);
            long before = stock();
            Result original = createBuyNowSession(1, UUID.randomUUID().toString());
            long oldSessionId = original.json.path("checkoutSessionId").asLong();
            Result oldAttempt = startSessionAttempt(oldSessionId, UUID.randomUUID().toString());
            String oldRef = oldAttempt.json.path("attemptReference").asText();
            String cents = VnpayGateway.amount(original.json.path("totalAmount").decimalValue());
            Map<String, String> cancel = new TreeMap<>(Map.of("vnp_TmnCode", "TESTONLY", "vnp_TxnRef", oldRef,
                    "vnp_Amount", cents, "vnp_ResponseCode", "24", "vnp_TransactionStatus", "02", "vnp_TransactionNo", "0"));
            cancel.put("vnp_SecureHash", gateway.sign(VnpayGateway.canonical(cancel)));

            Map<String, String> invalid = new TreeMap<>(cancel);
            invalid.put("vnp_SecureHash", "0".repeat(128));
            assertEquals(400, call("POST", "/api/payments/vnpay/return", customer, invalid, null).status);
            assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM payment_attempts WHERE merchant_reference=?", String.class, oldRef));
            assertEquals("HELD", jdbc.queryForObject("SELECT reservation_status FROM checkout_sessions WHERE checkout_session_id=?", String.class, oldSessionId));
            assertEquals(before - 1, stock());

            assertEquals(200, call("POST", "/api/payments/vnpay/return", customer, cancel, null).status);
            assertEquals("FAILED_FINAL", jdbc.queryForObject("SELECT status FROM payment_attempts WHERE merchant_reference=?", String.class, oldRef));
            assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM checkout_sessions WHERE checkout_session_id=?", String.class, oldSessionId));
            assertEquals("RELEASED", jdbc.queryForObject("SELECT reservation_status FROM checkout_sessions WHERE checkout_session_id=?", String.class, oldSessionId));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?", Integer.class, oldSessionId));
            assertEquals(before, stock());
            assertEquals(200, call("POST", "/api/payments/vnpay/return", customer, cancel, null).status);
            assertEquals("00", sessionIpn(oldRef, "24", "02", "0", cents).json.path("RspCode").asText());
            assertEquals(before, stock());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?", Integer.class, oldSessionId));

            Result fresh = call("POST", "/api/checkout-sessions", customer,
                    Map.of("sourceType", "BUY_NOW", "buyNowProductId", productId, "buyNowQuantity", 1, "shipping", shipping()),
                    UUID.randomUUID().toString());
            assertEquals(201, fresh.status, String.valueOf(fresh.json));
            long freshSessionId = fresh.json.path("checkoutSessionId").asLong();
            assertNotEquals(oldSessionId, freshSessionId);
            assertEquals("FAILED_FINAL", jdbc.queryForObject("SELECT status FROM payment_attempts WHERE merchant_reference=?", String.class, oldRef));
            assertEquals("CANCELLED", jdbc.queryForObject("SELECT status FROM checkout_sessions WHERE checkout_session_id=?", String.class, oldSessionId));
            assertEquals(before - 1, stock());
            if ("COD".equals(nextMethod)) {
                Result cod = call("POST", "/api/checkout-sessions/" + freshSessionId + "/confirm-cod", customer, null, null);
                assertEquals(200, cod.status);
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?", Integer.class, freshSessionId));
                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?", Integer.class, oldSessionId));
                assertEquals(before - 1, stock());
            } else {
                Result freshAttempt = startSessionAttempt(freshSessionId, UUID.randomUUID().toString());
                String freshRef = freshAttempt.json.path("attemptReference").asText();
                assertNotEquals(oldRef, freshRef);
                assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM payment_attempts WHERE merchant_reference=?", String.class, freshRef));
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempts WHERE checkout_session_id=?", Integer.class, freshSessionId));
                assertEquals("00", sessionIpn(freshRef, "24", "02", "0", VnpayGateway.amount(freshAttempt.json.path("session").path("totalAmount").decimalValue())).json.path("RspCode").asText());
                assertEquals("RELEASED", jdbc.queryForObject("SELECT reservation_status FROM checkout_sessions WHERE checkout_session_id=?", String.class, freshSessionId));
                assertEquals(before, stock());
            }
        }
    }
}
