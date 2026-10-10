package vn.teasmart.backend.admin;
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
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="PAYMENT_TEST_SERVER_URL",matches="jdbc:mysql://.*")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminManagementMysqlTest {
    static final String DB="teasmart_admin_test_"+UUID.randomUUID().toString().replace("-","");
    static final String PASSWORD=UUID.randomUUID().toString();
    static final List<String> TABLES=new ArrayList<>();
    static final JsonMapper JSON=JsonMapper.builder().build();
    static String admin,customer; static int requests;
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) throws Exception {
        String server=System.getenv("PAYMENT_TEST_SERVER_URL");
        if(!server.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1):[0-9]+/")) throw new IllegalArgumentException("Local test server only.");
        try(var c=DriverManager.getConnection(server,System.getenv("PAYMENT_TEST_USERNAME"),System.getenv("PAYMENT_TEST_PASSWORD"));var s=c.createStatement()) {
            s.execute("CREATE DATABASE `"+DB+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        try(var c=DriverManager.getConnection(server+DB,System.getenv("PAYMENT_TEST_USERNAME"),System.getenv("PAYMENT_TEST_PASSWORD"));var s=c.createStatement()) {
            for(String part:Files.readString(Path.of("database/schema.sql")).replaceAll("(?m)^--.*$","").split(";")) {
                String sql=part.trim(); if(sql.isEmpty()||sql.startsWith("CREATE DATABASE")||sql.startsWith("USE "))continue;
                if(!sql.startsWith("CREATE TABLE"))throw new IllegalArgumentException("Unexpected fixture schema.");
                s.execute(sql);var m=java.util.regex.Pattern.compile("CREATE TABLE `?([a-z_]+)").matcher(sql);assertTrue(m.find());TABLES.add(m.group(1));
            }
        }
        byte[] secret=new byte[48];new java.security.SecureRandom().nextBytes(secret);
        r.add("spring.datasource.url",()->server+DB);r.add("spring.datasource.username",()->System.getenv("PAYMENT_TEST_USERNAME"));r.add("spring.datasource.password",()->System.getenv("PAYMENT_TEST_PASSWORD"));
        r.add("teasmart.jwt.secret-base64",()->Base64.getEncoder().encodeToString(secret));r.add("teasmart.jwt.issuer",()->"admin-tests");
        r.add("teasmart.vnpay.enabled",()->false);r.add("teasmart.vnpay.reconciliation-delay-ms",()->86400000);
    }
    record Result(int status,JsonNode data,String raw){}
    Result call(String method,String path,String token,Object body)throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(java.time.Duration.ofSeconds(20));
        if(token!=null)b.header("Authorization","Bearer "+token);
        if(body!=null)b.header("Content-Type","application/json");
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        var response=HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());synchronized(AdminManagementMysqlTest.class){requests++;}
        return new Result(response.statusCode(),response.body().isBlank()?null:JSON.readTree(response.body()),response.body());
    }
    String login(String email)throws Exception {var r=call("POST","/api/auth/login",null,Map.of("email",email,"password",PASSWORD));assertEquals(200,r.status);return r.data.get("accessToken").stringValue();}
    @Test @Order(1) void seedAndLogin()throws Exception {
        String hash=new BCryptPasswordEncoder().encode(PASSWORD);
        for(String[] u:new String[][]{{"admin@example.invalid","ADMIN","ACTIVE"},{"customer@example.invalid","CUSTOMER","ACTIVE"},{"second@example.invalid","CUSTOMER","INACTIVE"},{"percent%@example.invalid","CUSTOMER","ACTIVE"}})
            jdbc.update("INSERT INTO users(full_name,email,password_hash,phone,role,status,created_at,updated_at) VALUES('Fixture',?,?,'0912345678',?,?,'2026-01-01','2026-01-01')",u[0],hash,u[1],u[2]);
        jdbc.update("INSERT INTO categories(name,slug,status,created_at,updated_at) VALUES('Test','test','ACTIVE',NOW(),NOW())");
        jdbc.update("INSERT INTO tea_regions(name,status,created_at,updated_at) VALUES('Test','ACTIVE',NOW(),NOW())");
        jdbc.update("INSERT INTO stores(name,status,created_at,updated_at) VALUES('Test','ACTIVE',NOW(),NOW())");
        jdbc.update("INSERT INTO products(category_id,region_id,store_id,name,slug,price,stock_quantity,weight_grams,status,created_at,updated_at) VALUES(1,1,1,'Test','test',100,20,100,'ACTIVE',NOW(),NOW())");
        String[] states={"DELIVERED","PENDING","CANCELLED","DELIVERED","PENDING","SHIPPING"};
        String[] paidDates={"2026-10-01 00:00:00",null,"2026-10-03 23:59:59","2026-09-30 23:59:59","2026-10-04 00:00:00",null};
        for(int i=0;i<6;i++) {
            jdbc.update("INSERT INTO orders(order_code,user_id,recipient_name,recipient_phone,shipping_address,subtotal,shipping_fee,total_amount,order_status,created_at,updated_at) VALUES(?,2,'Test','0912345678','Test',?,0,?,?,'2026-10-02','2026-10-02')","TEST-"+i,(i+1)*100,(i+1)*100,states[i]);
            jdbc.update("INSERT INTO payments(order_id,payment_method,amount,payment_status,paid_at,created_at,updated_at) VALUES(?,'COD',?,?,?,?,?)",i+1,(i+1)*100,paidDates[i]==null?(i==1?"PENDING":"FAILED"):"PAID",paidDates[i],"2025-01-01","2025-01-01");
        }
        // Two items on one order must not duplicate its Payment revenue.
        for(int i=0;i<2;i++)jdbc.update("INSERT INTO order_items(order_id,product_id,product_name,unit_price,quantity,subtotal) VALUES(1,1,'Test',50,1,50)");
        admin=login("admin@example.invalid");customer=login("customer@example.invalid");
    }
    @Test @Order(2) void dashboardPaidDateBoundariesAndNoDoubleCounting()throws Exception {
        var r=call("GET","/api/admin/dashboard?from=2026-10-01&to=2026-10-03",admin,null);assertEquals(200,r.status);
        assertEquals(400,r.data.get("revenue").decimalValue().intValueExact());assertEquals(2,r.data.get("paidPayments").asInt());assertEquals(6,r.data.get("ordersCount").asInt());
        assertEquals(1,r.data.get("productsCount").asInt());assertEquals(3,r.data.get("customersCount").asInt());
        assertEquals("Asia/Ho_Chi_Minh",r.data.get("timezone").stringValue());assertEquals(3,r.data.get("revenueByDay").size());
        assertEquals(0,r.data.get("revenueByDay").get(1).get("revenue").asInt());assertEquals(1,r.data.get("ordersByStatus").get("CANCELLED").asInt());
        r=call("GET","/api/admin/dashboard?from=2000-01-01&to=2000-01-02",admin,null);assertEquals(200,r.status);assertEquals(0,r.data.get("revenue").asInt());assertEquals(0,r.data.get("ordersCount").asInt());
        assertEquals(200,call("GET","/api/admin/dashboard",admin,null).status);
    }
    @Test @Order(3) void customerListSearchPagingDetailAndPrivacy()throws Exception {
        var r=call("GET","/api/admin/customers?size=1",admin,null);assertEquals(200,r.status);assertEquals(3,r.data.get("totalElements").asInt());assertEquals(3,r.data.get("totalPages").asInt());assertEquals(4,r.data.get("content").get(0).get("userId").asInt());
        r=call("GET","/api/admin/customers?keyword=%20CUSTOMER%40%20",admin,null);assertEquals(200,r.status);assertEquals(1,r.data.get("totalElements").asInt());
        r=call("GET","/api/admin/customers?keyword=%25",admin,null);assertEquals(1,r.data.get("totalElements").asInt());
        r=call("GET","/api/admin/customers?status=INACTIVE",admin,null);assertEquals(1,r.data.get("totalElements").asInt());
        r=call("GET","/api/admin/customers/2",admin,null);assertEquals(200,r.status);assertEquals(6,r.data.get("ordersCount").asInt());assertEquals(1300,r.data.get("totalSpent").decimalValue().intValueExact());assertFalse(r.raw.contains("password"));assertFalse(r.raw.contains("token"));assertFalse(r.raw.contains("avatarUrl"));
        assertEquals(404,call("GET","/api/admin/customers/1",admin,null).status);assertEquals(404,call("GET","/api/admin/customers/999999",admin,null).status);
    }
    @Test @Order(4) void validationAndAuthorization()throws Exception {
        for(String path:List.of("/api/admin/dashboard","/api/admin/customers","/api/admin/customers/2")) {
            assertEquals(401,call("GET",path,null,null).status);assertEquals(401,call("GET",path,"invalid-token",null).status);assertEquals(403,call("GET",path,customer,null).status);
        }
        assertEquals(401,call("PATCH","/api/admin/customers/2/status",null,Map.of("status","INACTIVE")).status);
        assertEquals(403,call("PATCH","/api/admin/customers/2/status",customer,Map.of("status","INACTIVE")).status);
        for(String path:List.of("/api/admin/customers?page=-1","/api/admin/customers?size=0","/api/admin/customers?size=51","/api/admin/customers?status=ADMIN","/api/admin/customers/0","/api/admin/dashboard?from=wrong","/api/admin/dashboard?from=2026-10-03&to=2026-10-01","/api/admin/dashboard?from=2020-01-01&to=2026-01-01"))assertEquals(400,call("GET",path,admin,null).status);
        assertEquals(400,call("PATCH","/api/admin/customers/2/status",admin,Map.of("status","ACTIVE","role","ADMIN")).status);
        assertEquals(400,call("PATCH","/api/admin/customers/2/status",admin,Map.of("status","BLOCKED")).status);
        assertEquals(404,call("PATCH","/api/admin/customers/1/status",admin,Map.of("status","INACTIVE")).status);
    }
    @Test @Order(5) void lockingRevokesJwtAndLoginAndIsIdempotent()throws Exception {
        var before=jdbc.queryForList("SELECT * FROM payments");var ordersBefore=jdbc.queryForList("SELECT * FROM orders");
        assertEquals(200,call("PATCH","/api/admin/customers/2/status",admin,Map.of("status","INACTIVE")).status);
        var stamp=jdbc.queryForObject("SELECT updated_at FROM users WHERE user_id=2",Object.class);
        assertEquals(200,call("PATCH","/api/admin/customers/2/status",admin,Map.of("status","INACTIVE")).status);assertEquals(stamp,jdbc.queryForObject("SELECT updated_at FROM users WHERE user_id=2",Object.class));
        assertEquals(401,call("GET","/api/users/me",customer,null).status);assertEquals(401,call("POST","/api/auth/login",null,Map.of("email","customer@example.invalid","password",PASSWORD)).status);
        assertEquals(200,call("PATCH","/api/admin/customers/2/status",admin,Map.of("status","ACTIVE")).status);customer=login("customer@example.invalid");assertEquals(200,call("GET","/api/users/me",customer,null).status);
        var pool=Executors.newFixedThreadPool(2);
        try {var a=pool.submit(()->call("PATCH","/api/admin/customers/2/status",admin,Map.of("status","INACTIVE")));var b=pool.submit(()->call("PATCH","/api/admin/customers/2/status",admin,Map.of("status","ACTIVE")));assertEquals(200,a.get(20,TimeUnit.SECONDS).status);assertEquals(200,b.get(20,TimeUnit.SECONDS).status);}finally{pool.shutdownNow();}
        assertEquals(before,jdbc.queryForList("SELECT * FROM payments"));assertEquals(ordersBefore,jdbc.queryForList("SELECT * FROM orders"));assertEquals(20,jdbc.queryForObject("SELECT stock_quantity FROM products WHERE product_id=1",Long.class));
        assertEquals(200,call("GET","/api/products",null,null).status);assertEquals(200,call("GET","/api/admin/orders",admin,null).status);
    }
    @AfterAll static void cleanup()throws Exception {
        try(var c=DriverManager.getConnection(System.getenv("PAYMENT_TEST_SERVER_URL")+DB,System.getenv("PAYMENT_TEST_USERNAME"),System.getenv("PAYMENT_TEST_PASSWORD"));var s=c.createStatement()) {
            var reversed=new ArrayList<>(TABLES);Collections.reverse(reversed);for(String t:reversed)s.executeUpdate("DELETE FROM `"+t+"`");for(String t:TABLES)try(var rs=s.executeQuery("SELECT COUNT(*) FROM `"+t+"`")){rs.next();assertEquals(0,rs.getInt(1));}
        }
        System.out.println("Admin HTTP requests: "+requests+"; fixture cleanup PASS; no database dropped.");
    }
}
