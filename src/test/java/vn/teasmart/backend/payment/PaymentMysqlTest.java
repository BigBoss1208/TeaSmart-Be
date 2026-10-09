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
    private static int requests;
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired VnpayGateway gateway;
    @Autowired PaymentService payments;
    @Autowired OrderStockService stock;
    @Autowired vn.teasmart.backend.repository.OrderRepository orderRepository;
    @Autowired vn.teasmart.backend.repository.PaymentRepository paymentRepository;
    @Autowired vn.teasmart.backend.repository.UserRepository userRepository;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final HttpClient http = HttpClient.newHttpClient();
    private static String customer, admin, other;

    @DynamicPropertySource static void database(DynamicPropertyRegistry r) throws Exception {
        String server = System.getenv("PAYMENT_TEST_SERVER_URL");
        if (!server.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1):[0-9]+/")) throw new IllegalArgumentException("Use a local server URL with no database or query.");
        String url = server + DB;
        try (Connection c = DriverManager.getConnection(server, USER, PASSWORD); Statement s = c.createStatement()) {
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
        }
        byte[] jwt = new byte[32]; new java.security.SecureRandom().nextBytes(jwt);
        r.add("spring.datasource.url", () -> url); r.add("spring.datasource.username", () -> USER); r.add("spring.datasource.password", () -> PASSWORD);
        r.add("teasmart.jwt.secret-base64", () -> Base64.getEncoder().encodeToString(jwt)); r.add("teasmart.jwt.issuer", () -> "payment-tests");
        r.add("teasmart.vnpay.enabled", () -> true); r.add("teasmart.vnpay.tmn-code", () -> "TESTONLY");
        r.add("teasmart.vnpay.hash-secret", () -> SECRET); r.add("teasmart.vnpay.return-url", () -> "http://localhost:3000/payment/vnpay-return");
        r.add("teasmart.vnpay.reconciliation-delay-ms", () -> 86400000);
        System.out.println("Payment migration test database: " + DB);
    }
    @BeforeAll static void noExternalGateway() { /* All callback inputs are generated locally; no merchant credentials. */ }
    @AfterAll static void cleanup() throws Exception {
        String server = System.getenv("PAYMENT_TEST_SERVER_URL");
        try (Connection c = DriverManager.getConnection(server + DB, USER, PASSWORD); Statement s = c.createStatement()) {
            var reversed = new ArrayList<>(TABLES); Collections.reverse(reversed);
            for (String table : reversed) s.executeUpdate("DELETE FROM `" + table + "`");
            for (String table : TABLES) try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
                rs.next(); assertEquals(0, rs.getInt(1));
            }
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
        assertEquals(200, call("POST", "/api/cart/items", customer, Map.of("productId", 1, "quantity", 2), null).status);
        var r = call("POST", online ? "/api/orders/vnpay" : "/api/orders", customer, shipping(), key);
        assertEquals(201, r.status); var order = online ? r.json.get("order") : r.json;
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
    private long stock() { return jdbc.queryForObject("SELECT stock_quantity FROM products WHERE product_id=1", Long.class); }

    @Test @Order(1) void migrationPreservesLegacyAndCreatesExactColumns() throws Exception {
        assertEquals(19, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class));
        assertEquals(33, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name IN ('orders','payments')", Integer.class)); // 22 baseline + 11 additions = 33
        assertNull(jdbc.queryForObject("SELECT stock_released_at FROM orders WHERE order_id=1", Object.class));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT order_status FROM orders WHERE order_id=1", String.class));
        assertEquals(false, jdbc.queryForObject("SELECT reconciliation_required FROM payments WHERE order_id=1", Boolean.class));
        assertEquals("2001-01-01 00:00:00", jdbc.queryForObject("SELECT CAST(paid_at AS CHAR) FROM payments WHERE order_id=1", String.class) == null
                ? jdbc.queryForObject("SELECT CAST(created_at AS CHAR) FROM payments WHERE order_id=1", String.class) : "bad");
        String hash = new BCryptPasswordEncoder().encode(TEST_PASSWORD);
        for (String[] u : new String[][] {{"customer@example.invalid", "CUSTOMER"}, {"admin@example.invalid", "ADMIN"}, {"other@example.invalid", "CUSTOMER"}})
            jdbc.update("INSERT INTO users(full_name,email,password_hash,role,status,created_at,updated_at) VALUES('Fixture',?,?,?,'ACTIVE',NOW(),NOW())", u[0], hash, u[1]);
        jdbc.update("INSERT INTO categories(name,slug,status,created_at,updated_at) VALUES('Test','test','ACTIVE',NOW(),NOW())");
        jdbc.update("INSERT INTO tea_regions(name,status,created_at,updated_at) VALUES('Test','ACTIVE',NOW(),NOW())");
        jdbc.update("INSERT INTO stores(name,status,created_at,updated_at) VALUES('Test','ACTIVE',NOW(),NOW())");
        jdbc.update("INSERT INTO products(category_id,region_id,store_id,name,slug,price,stock_quantity,weight_grams,status,created_at,updated_at) VALUES(1,1,1,'Test tea','test-tea',100000,100,100,'ACTIVE',NOW(),NOW())");
        customer = login("customer@example.invalid"); admin = login("admin@example.invalid"); other = login("other@example.invalid");
    }
    @Test @Order(2) void codConfirmationAndReplay() throws Exception {
        String key = UUID.randomUUID().toString(); long before = stock(); long id = checkout(false, key);
        assertEquals(before - 2, stock());
        assertEquals(200, call("POST", "/api/orders", customer, shipping(), key).status); assertEquals(before - 2, stock());
        assertEquals(409, call("POST", "/api/orders", customer, Map.of("recipientName", "Other", "recipientPhone", "0912345678", "shippingAddress", "Other"), key).status);
        assertEquals(409, call("PATCH", "/api/admin/orders/" + id + "/payment/confirm-cod", admin, null, null).status);
        for (String s : List.of("CONFIRMED", "SHIPPING", "DELIVERED")) assertEquals(200, call("PATCH", "/api/admin/orders/" + id + "/status", admin, Map.of("status", s), null).status);
        assertEquals(200, call("PATCH", "/api/admin/orders/" + id + "/payment/confirm-cod", admin, null, null).status);
        var time = jdbc.queryForObject("SELECT paid_at FROM payments WHERE order_id=?", Timestamp.class, id);
        assertEquals(200, call("PATCH", "/api/admin/orders/" + id + "/payment/confirm-cod", admin, null, null).status);
        assertEquals(time, jdbc.queryForObject("SELECT paid_at FROM payments WHERE order_id=?", Timestamp.class, id));
        assertNotNull(jdbc.queryForObject("SELECT confirmed_by_admin_id FROM payments WHERE order_id=?", Long.class, id));
    }
    @Test @Order(3) void codCancelAndLegacyMarkerNeverRestoreTwice() throws Exception {
        long before = stock(), id = checkout(false, UUID.randomUUID().toString());
        assertEquals(200, call("PATCH", "/api/orders/" + id + "/cancel", customer, null, null).status);
        assertEquals(before, stock()); assertNotNull(jdbc.queryForObject("SELECT stock_released_at FROM orders WHERE order_id=?", Timestamp.class, id));
        assertEquals(409, call("PATCH", "/api/orders/" + id + "/cancel", customer, null, null).status); assertEquals(before, stock());
        assertEquals("PENDING", jdbc.queryForObject("SELECT payment_status FROM payments WHERE order_id=?", String.class, id));
    }
    @Test @Order(4) void vnpayDuplicateConcurrentAndFulfillment() throws Exception {
        String key = UUID.randomUUID().toString(); long id = checkout(true, key), after = stock();
        assertEquals(409, call("PATCH", "/api/orders/" + id + "/cancel", customer, null, null).status);
        assertEquals(409, call("PATCH", "/api/admin/orders/" + id + "/status", admin, Map.of("status", "CONFIRMED"), null).status);
        assertEquals("04", ipn(id, "00", "00", "1001", "1").json.get("RspCode").asText());
        var pool = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<Future<Result>>(); for (int i=0;i<4;i++) futures.add(pool.submit(() -> ipn(id,"00","00","1001","20000000")));
            var codes = new ArrayList<String>(); for (var f : futures) codes.add(f.get(30, TimeUnit.SECONDS).json.get("RspCode").asText());
            assertEquals(1, Collections.frequency(codes,"00")); assertEquals(3, Collections.frequency(codes,"02"));
        } finally { pool.shutdownNow(); }
        assertEquals(after, stock()); assertEquals("PAID", jdbc.queryForObject("SELECT payment_status FROM payments WHERE order_id=?", String.class,id));
        assertEquals(200, call("POST", "/api/orders/vnpay", customer, shipping(), key).status);
        for (String s : List.of("CONFIRMED","SHIPPING","DELIVERED")) assertEquals(200, call("PATCH", "/api/admin/orders/"+id+"/status",admin,Map.of("status",s),null).status);
    }
    @Test @Order(5) void failureWithoutVerifiedFinalityKeepsStockAndPaidCannotDowngrade() throws Exception {
        long id = checkout(true,UUID.randomUUID().toString()), after=stock();
        assertEquals("00",ipn(id,"24","02","0","20000000").json.get("RspCode").asText());
        assertEquals("PENDING",jdbc.queryForObject("SELECT payment_status FROM payments WHERE order_id=?",String.class,id));
        assertEquals(true,jdbc.queryForObject("SELECT reconciliation_required FROM payments WHERE order_id=?",Boolean.class,id)); assertEquals(after,stock());
        assertEquals("00",ipn(id,"00","00","1002","20000000").json.get("RspCode").asText());
        assertEquals("00",ipn(id,"24","02","0","20000000").json.get("RspCode").asText());
        assertEquals("PAID",jdbc.queryForObject("SELECT payment_status FROM payments WHERE order_id=?",String.class,id)); assertEquals(after,stock());
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
        long before = stock(), id = checkout(true, UUID.randomUUID().toString());
        var p = payments.snapshot(id);
        var notification = new VnpayGateway.Notification(p.reference(), new java.math.BigDecimal("200000"), "24", "02", "0", null);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        var policy = new PaymentService(orderRepository, paymentRepository, userRepository, stock, em, "24:02");
        assertThrows(IllegalStateException.class, () -> tx.execute(status -> {
            assertEquals("00", policy.accept(notification, false));
            throw new IllegalStateException("Controlled rollback after flush");
        }));
        assertEquals(before - 2, stock());
        assertEquals("PENDING", jdbc.queryForObject("SELECT payment_status FROM payments WHERE order_id=?", String.class, id));
        assertNull(jdbc.queryForObject("SELECT stock_released_at FROM orders WHERE order_id=?", Object.class, id));
        jdbc.update("UPDATE products SET stock_quantity=4294967295 WHERE product_id=1");
        assertThrows(vn.teasmart.backend.exception.OrderConflictException.class, () -> tx.execute(status -> policy.accept(notification, false)));
        assertEquals("PENDING", jdbc.queryForObject("SELECT order_status FROM orders WHERE order_id=?", String.class, id));
        jdbc.update("UPDATE products SET stock_quantity=? WHERE product_id=1", before - 2);
        assertEquals("00", tx.execute(status -> policy.accept(notification, false)));
        assertEquals(before, stock());
        assertEquals("02", tx.execute(status -> policy.accept(notification, false))); assertEquals(before, stock());
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT order_status FROM orders WHERE order_id=?", String.class, id));
        assertEquals("FAILED", jdbc.queryForObject("SELECT payment_status FROM payments WHERE order_id=?", String.class, id));
        assertEquals("00", ipn(id,"00","00","1003","20000000").json.get("RspCode").asText());
        assertEquals("PAID", jdbc.queryForObject("SELECT payment_status FROM payments WHERE order_id=?", String.class, id));
        assertEquals(true, jdbc.queryForObject("SELECT reconciliation_required FROM payments WHERE order_id=?", Boolean.class, id));
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT order_status FROM orders WHERE order_id=?", String.class, id)); assertEquals(before, stock());
    }
    @Test @Order(8) void concurrentCheckoutOnlyCreatesOneOrderAndOneStockDeduction() throws Exception {
        assertEquals(200, call("POST", "/api/cart/items", customer, Map.of("productId",1,"quantity",2),null).status);
        long before=stock(); String key=UUID.randomUUID().toString();
        var pool=Executors.newFixedThreadPool(3);
        try {
            var futures=new ArrayList<Future<Result>>();
            for(int i=0;i<3;i++) futures.add(pool.submit(() -> call("POST","/api/orders/vnpay",customer,shipping(),key)));
            Set<Long> ids=new HashSet<>(); List<Integer> codes=new ArrayList<>();
            for(var f:futures) {var result=f.get(30,TimeUnit.SECONDS); codes.add(result.status); ids.add(result.json.get("order").get("orderId").asLong());}
            assertEquals(1,ids.size()); assertEquals(1,Collections.frequency(codes,201)); assertEquals(2,Collections.frequency(codes,200));
        } finally {pool.shutdownNow();}
        assertEquals(before-2,stock());
    }
    @Test @Order(9) void schemaConstraintsAndOfflineReconciliation() throws Exception {
        long id=checkout(true,UUID.randomUUID().toString()); long after=stock();
        var ref=payments.snapshot(id).reference();
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update("UPDATE payments SET merchant_reference=? WHERE order_id<>? AND gateway='VNPAY' LIMIT 1",ref,id));
        var p=payments.snapshot(id);
        var query=org.mockito.Mockito.mock(VnpayQueryClient.class);
        org.mockito.Mockito.when(query.query(org.mockito.Mockito.any())).thenReturn(null);
        new PaymentReconciliationService(payments,query).reconcile(id);
        assertEquals("PENDING",jdbc.queryForObject("SELECT payment_status FROM payments WHERE order_id=?",String.class,id));
        assertNotNull(jdbc.queryForObject("SELECT last_reconciliation_at FROM payments WHERE order_id=?",Timestamp.class,id));
        assertEquals(after,stock());
        assertEquals(200,call("GET","/api/orders/"+id,customer,null,null).status);
        String userId=jdbc.queryForObject("SELECT CAST(user_id AS CHAR) FROM orders WHERE order_id=?",String.class,id);
        var response=payments.customerStatusByReference(Long.valueOf(userId),ref); assertEquals(id,response.orderId());
        assertThrows(vn.teasmart.backend.exception.ResourceNotFoundException.class, () -> payments.customerStatusByReference(1L,ref));
    }

}
