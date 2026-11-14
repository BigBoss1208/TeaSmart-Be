package vn.teasmart.backend.seller;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import vn.teasmart.backend.security.GoogleTokenVerifier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="PAYMENT_TEST_SERVER_URL",matches="jdbc:mysql://.*")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SellerMysqlTest {
    static final String DB="teasmart_seller_test_"+UUID.randomUUID().toString().replace("-","");
    static final String PASSWORD=UUID.randomUUID().toString();
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final List<String> TABLES=new ArrayList<>();
    static String admin,a,b; static long appA,shopA,shopB;
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired SellerFoundation foundation;
    @Autowired vn.teasmart.backend.service.VnpayGateway gateway;
    @MockitoBean GoogleTokenVerifier google;
    @MockitoBean vn.teasmart.backend.service.GoogleCodeClient googleCodes;
    @MockitoBean vn.teasmart.backend.storage.ReviewImageStorage storage;
    @DynamicPropertySource static void database(DynamicPropertyRegistry r)throws Exception {
        String server=System.getenv("PAYMENT_TEST_SERVER_URL");
        if(!server.matches("jdbc:mysql://(localhost|127\\.0\\.0\\.1):33377/"))throw new IllegalArgumentException("Isolated local test server on port 33377 only");
        if(!DB.matches("teasmart_seller_test_[a-f0-9]{32}"))throw new IllegalStateException();
        try(var c=DriverManager.getConnection(server,System.getenv("PAYMENT_TEST_USERNAME"),System.getenv("PAYMENT_TEST_PASSWORD"));var s=c.createStatement()){
            try(var identity=s.executeQuery("SELECT @@port")) {
                if(!identity.next() || identity.getInt(1)!=33377)throw new IllegalStateException("Refusing to write outside isolated MySQL test");
            }
            s.execute("CREATE DATABASE `"+DB+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        try(var c=connection();var s=c.createStatement()){
            for(String part:Files.readString(Path.of("database/schema.sql")).replaceAll("(?m)^--.*$","").split(";")){
                String sql=part.trim();if(sql.isEmpty()||sql.startsWith("CREATE DATABASE")||sql.startsWith("USE "))continue;
                if(!sql.startsWith("CREATE TABLE"))throw new IllegalArgumentException("Unexpected schema statement");
                s.execute(sql);
                var m=java.util.regex.Pattern.compile("CREATE TABLE `?([a-z_]+)").matcher(sql);assertTrue(m.find());TABLES.add(m.group(1));
            }
            for(String part:Files.readString(Path.of("database/migrations/1027_checkout_sessions_payment_attempts.sql")).replaceAll("(?m)^--.*$","").split(";")) {
                if(!part.isBlank())s.execute(part.trim());
            }
            s.execute(Files.readString(Path.of("database/migrations/1111_vnpay_checkout_audit.sql")));
            TABLES.add("vnpay_checkout_audit");
            // Existing rows prove that the additive migration preserves data and stock.
            s.execute("INSERT INTO users(full_name,email,role,status,created_at,updated_at) VALUES('Legacy','legacy@example.invalid','CUSTOMER','ACTIVE','2001-01-01','2001-01-01')");
            s.execute("INSERT INTO orders(order_code,user_id,recipient_name,recipient_phone,shipping_address,subtotal,shipping_fee,total_amount,order_status,created_at,updated_at) VALUES('LEGACY',1,'Legacy','0912345678','Test',100,0,100,'CANCELLED','2001-01-01','2001-01-01')");
            s.execute("INSERT INTO payments(order_id,payment_method,amount,payment_status,created_at,updated_at) VALUES(1,'COD',100,'PENDING','2001-01-01','2001-01-01')");
            String migration=Files.readString(Path.of("database/migrations/1104_seller_lite_phase1.sql"));
            assertFalse(migration.matches("(?is).*\\b(DROP|DELETE|ALTER|UPDATE|TRUNCATE)\\s+.*"));
            for(String part:migration.replaceAll("(?m)^--.*$","").split(";")){
                String sql=part.trim();if(sql.isEmpty())continue;
                if(!sql.startsWith("CREATE TABLE "))throw new IllegalArgumentException("Additive tables only");
                s.execute(sql);var m=java.util.regex.Pattern.compile("CREATE TABLE ([a-z_]+)").matcher(sql);assertTrue(m.find());TABLES.add(m.group(1));
            }
            s.execute("INSERT INTO checkout_sessions(session_code,user_id,idempotency_key,request_hash,source_type,recipient_name,recipient_phone,shipping_address,subtotal,shipping_fee,total_amount,status,reservation_status,expires_at,reservation_expires_at,created_at,updated_at) VALUES('BEFORE1105',1,'before1105',REPEAT('a',64),'CART','Legacy','0912345678','Test',100,0,100,'RECONCILIATION_REQUIRED','HELD',NOW(),NOW(),NOW(),NOW())");
            s.execute("INSERT INTO payment_attempts(checkout_session_id,attempt_number,gateway,merchant_reference,amount,status,reconciliation_required,created_at,updated_at) SELECT checkout_session_id,1,'VNPAY','BEFORE1105',100,'UNKNOWN',TRUE,NOW(),NOW() FROM checkout_sessions WHERE session_code='BEFORE1105'");
            var before=new LinkedHashMap<String,String>();
            var columns=new LinkedHashMap<String,String>();
            for(String table:List.of("users","orders","payments","checkout_sessions","payment_attempts")) {
                try(var rs=s.executeQuery("SELECT * FROM "+table)) {
                    var names=new ArrayList<String>();for(int i=1;i<=rs.getMetaData().getColumnCount();i++)names.add(rs.getMetaData().getColumnName(i));
                    columns.put(table,String.join(",",names));before.put(table,snapshot(rs));
                }
            }
            for(String part:Files.readString(Path.of("database/migrations/1105_seller_foundation.sql")).replaceAll("(?m)^--.*$","").split(";")) {
                if(!part.isBlank())s.execute(part.trim());
            }
            for(String table:columns.keySet())try(var rs=s.executeQuery("SELECT "+columns.get(table)+" FROM "+table)){assertEquals(before.get(table),snapshot(rs),"Migration changed "+table);}
            for(String part:Files.readString(Path.of("database/migrations/1106_seller_products.sql")).replaceAll("(?m)^--.*$","").split(";"))if(!part.isBlank())s.execute(part.trim());
            // Remove these synthetic migration probes before the lifecycle tests.
            for(String part:Files.readString(Path.of("database/migrations/1107_seller_profile_details.sql")).replaceAll("(?m)^--.*$","").split(";"))if(!part.isBlank())s.execute(part.trim());
            for(String part:Files.readString(Path.of("database/migrations/1108_seller_product_archive.sql")).replaceAll("(?m)^--.*$","").split(";"))if(!part.isBlank())s.execute(part.trim());
            s.executeUpdate("DELETE FROM payment_attempts WHERE merchant_reference='BEFORE1105'");
            s.executeUpdate("DELETE FROM checkout_sessions WHERE session_code='BEFORE1105'");
            try(var rs=s.executeQuery("SELECT COUNT(*) FROM orders WHERE order_id=1 AND total_amount=100 AND shop_id IS NULL AND shop_name IS NULL")){rs.next();assertEquals(1,rs.getInt(1));}
            try(var rs=s.executeQuery("SELECT COUNT(*) FROM payments WHERE order_id=1 AND amount=100 AND payment_status='PENDING'")){rs.next();assertEquals(1,rs.getInt(1));}
        }
        byte[] secret=new byte[48];new java.security.SecureRandom().nextBytes(secret);
        r.add("spring.datasource.url",()->server+DB);r.add("spring.datasource.username",()->System.getenv("PAYMENT_TEST_USERNAME"));r.add("spring.datasource.password",()->System.getenv("PAYMENT_TEST_PASSWORD"));
        r.add("teasmart.jwt.secret-base64",()->Base64.getEncoder().encodeToString(secret));r.add("teasmart.jwt.issuer",()->"seller-tests");
        r.add("teasmart.seller.enabled",()->true);r.add("teasmart.vnpay.enabled",()->true);r.add("teasmart.vnpay.reconciliation-delay-ms",()->86400000);
        r.add("teasmart.seller.foundation-enabled",()->true);
        r.add("teasmart.seller.profile-details-enabled",()->true);
        r.add("teasmart.seller.product-archive-enabled",()->true);
        r.add("teasmart.vnpay.tmn-code",()->"TESTONLY");
        r.add("teasmart.vnpay.hash-secret",()->PASSWORD);
        r.add("teasmart.vnpay.return-url",()->"http://localhost:3000/payment/vnpay-return");
        r.add("teasmart.vnpay.verified-final-failure-pairs",()->"51:02");
        r.add("teasmart.vnpay.reconciliation-enabled",()->false);
        r.add("teasmart.google.enabled",()->true);r.add("teasmart.google.client-id",()->"fixture-client");r.add("teasmart.google.allowed-origins",()->"http://localhost:3000,http://127.0.0.1:3115,http://127.0.0.1:3116");
        r.add("teasmart.mail.enabled",()->false);
        System.out.println("Seller migration target: "+DB+"; additive migration and legacy row preservation PASS");
    }
    static String snapshot(ResultSet rs)throws SQLException {
        var rows=new ArrayList<String>();while(rs.next()){var values=new ArrayList<String>();for(int i=1;i<=rs.getMetaData().getColumnCount();i++)values.add(rs.getString(i));rows.add(values.toString());}Collections.sort(rows);return rows.toString();
    }
    static Connection connection()throws SQLException {
        return DriverManager.getConnection(System.getenv("PAYMENT_TEST_SERVER_URL")+DB,System.getenv("PAYMENT_TEST_USERNAME"),System.getenv("PAYMENT_TEST_PASSWORD"));
    }
    record Result(int status,JsonNode data,String raw){}
    Result call(String method,String path,String token,Object body)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(java.time.Duration.ofSeconds(20));
        if(token!=null)builder.header("Authorization","Bearer "+token);
        if(body!=null)builder.header("Content-Type","application/json");
        if((path.equals("/api/checkout-sessions")||path.endsWith("/payment-attempts/vnpay")||path.endsWith("/stock-adjustments")||path.startsWith("/api/orders"))&&method.equals("POST"))builder.header("Idempotency-Key",UUID.randomUUID().toString());
        var response=HttpClient.newHttpClient().send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(),response.body().isBlank()?null:JSON.readTree(response.body()),response.body());
    }
    Map<String,Object> profile(String name){return new HashMap<>(Map.of("shopName",name,"representativeName","Test Seller","phone","0912345678","businessAddress","Synthetic test address","description","Synthetic tea shop"));}
    String login(String email)throws Exception{var r=call("POST","/api/auth/login",null,Map.of("email",email,"password",PASSWORD));assertEquals(200,r.status,r.raw);return r.data.get("accessToken").stringValue();}
    @Test @Order(1) void registrationCannotGrantRolesAndPendingCannotSell()throws Exception{
        String hash=new BCryptPasswordEncoder().encode(PASSWORD);
        for(String[] u:new String[][]{{"admin","ADMIN"},{"seller-a","CUSTOMER"},{"seller-b","CUSTOMER"},{"plain","CUSTOMER"}})
            jdbc.update("INSERT INTO users(full_name,email,password_hash,role,status,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',NOW(),NOW())",u[0],u[0]+"@example.invalid",hash,u[1]);
        admin=login("admin@example.invalid");a=login("seller-a@example.invalid");b=login("seller-b@example.invalid");
        for(String role:List.of("ADMIN","SELLER")){
            assertEquals(400,call("POST","/api/auth/register",null,Map.of("fullName","Test","email","bad@example.invalid","phone","0912345678","password",PASSWORD,"role",role)).status);
        }
        var bad=profile("Bad");bad.put("role","SELLER");assertEquals(400,call("POST","/api/seller-applications",a,bad).status);
        assertEquals(403,call("GET","/api/seller/shop",a,null).status);
        var r=call("POST","/api/seller-applications",a,profile("A"));
        assertEquals(201,r.status,r.raw);appA=r.data.get("applicationId").longValue();
        assertEquals("PENDING",r.data.get("status").stringValue());
        assertEquals(409,call("POST","/api/seller-applications",a,profile("Duplicate")).status);
        assertEquals(403,call("GET","/api/seller/shop",a,null).status);
        assertEquals(403,call("POST","/api/admin/seller-applications/"+appA+"/approve",a,null).status);
        assertEquals(401,call("GET","/api/admin/seller-applications",null,null).status);
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM shops",Integer.class));
    }
    @Test @Order(2) void concurrentApprovalIsIdempotentAndOldJwtGainsSellerOnly()throws Exception{
        var pool=Executors.newFixedThreadPool(2);
        try{
            var jobs=pool.invokeAll(List.<Callable<Result>>of(()->call("POST","/api/admin/seller-applications/"+appA+"/approve",admin,null),()->call("POST","/api/admin/seller-applications/"+appA+"/approve",admin,null)));
            for(var job:jobs){var r=job.get();assertEquals(200,r.status,r.raw);shopA=r.data.get("shopId").longValue();}
        }finally{pool.shutdownNow();}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM shops",Integer.class));
        var me=call("GET","/api/users/me",a,null);assertEquals("CUSTOMER",me.data.get("role").stringValue());assertTrue(me.data.get("authorities").toString().contains("SELLER"));
        assertEquals(200,call("GET","/api/seller/shop",a,null).status);
        assertEquals(200,call("GET","/api/seller/shop",login("seller-a@example.invalid"),null).status);
        assertEquals(403,call("GET","/api/admin/shops",a,null).status);
        assertEquals(409,call("POST","/api/admin/seller-applications/"+appA+"/reject",admin,Map.of("reason","Too late")).status);
    }
    @Test @Order(3) void rejectionResubmissionAndConcurrentDuplicateSubmission()throws Exception{
        var pool=Executors.newFixedThreadPool(2);List<Integer> codes=new ArrayList<>();
        try{for(var f:pool.invokeAll(List.<Callable<Result>>of(()->call("POST","/api/seller-applications",b,profile("B")),()->call("POST","/api/seller-applications",b,profile("B")))))codes.add(f.get().status);}finally{pool.shutdownNow();}
        Collections.sort(codes);assertEquals(List.of(201,409),codes);
        long id=call("GET","/api/seller-applications/me",b,null).data.get("application").get("applicationId").longValue();
        assertEquals(400,call("POST","/api/admin/seller-applications/"+id+"/reject",admin,Map.of("reason"," ")).status);
        var rejected=call("POST","/api/admin/seller-applications/"+id+"/reject",admin,Map.of("reason","Please clarify origin"));
        assertEquals(200,rejected.status);assertEquals("Please clarify origin",rejected.data.get("rejectionReason").stringValue());
        assertEquals(403,call("GET","/api/seller/shop",b,null).status);
        assertEquals(409,call("POST","/api/admin/seller-applications/"+id+"/approve",admin,null).status);
        var retry=call("POST","/api/seller-applications",b,profile("B revised"));assertEquals(201,retry.status);
        assertNotEquals(id,retry.data.get("applicationId").longValue());
        var approved=call("POST","/api/admin/seller-applications/"+retry.data.get("applicationId").longValue()+"/approve",admin,null);
        assertEquals(200,approved.status);shopB=approved.data.get("shopId").longValue();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM seller_applications WHERE status='REJECTED'",Integer.class));
    }
    @Test @Order(4) void ownershipMassAssignmentAndSuspension()throws Exception{
        assertEquals(200,call("PATCH","/api/seller/shop",a,profile("A updated")).status);
        assertEquals("B revised",call("GET","/api/seller/shop",b,null).data.get("name").stringValue());
        for(String field:List.of("shopId","ownerUserId","status","role")){
            var body=profile("Injected");body.put(field,field.equals("shopId")?shopB:"ADMIN");
            assertEquals(400,call("PATCH","/api/seller/shop",a,body).status);
        }
        assertNotEquals(200,call("GET","/api/seller/shop/"+shopB,a,null).status);
        assertNotEquals(200,call("PATCH","/api/seller/shop/"+shopB,a,profile("Attack")).status);
        assertEquals(shopA,call("GET","/api/seller/shop?shopId="+shopB,a,null).data.get("shopId").longValue());
        assertEquals(400,call("PATCH","/api/admin/shops/"+shopA+"/status",admin,Map.of("status","SUSPENDED")).status);
        assertEquals(200,call("PATCH","/api/admin/shops/"+shopA+"/status",admin,Map.of("status","SUSPENDED","reason","Review required")).status);
        // Retrying approve must NOT reactivate the suspended Shop.
        assertEquals("SUSPENDED",call("POST","/api/admin/seller-applications/"+appA+"/approve",admin,null).data.get("status").stringValue());
        assertEquals(403,call("GET","/api/seller/shop",a,null).status);
        assertEquals(403,call("PATCH","/api/seller/shop",a,profile("Blocked")).status);
        assertFalse(call("GET","/api/users/me",a,null).data.get("authorities").toString().contains("SELLER"));
        assertEquals(200,call("GET","/api/cart",a,null).status);
        assertEquals("SUSPENDED",call("GET","/api/seller-applications/me",a,null).data.get("status").stringValue());
        assertEquals(200,call("PATCH","/api/admin/shops/"+shopA+"/status",admin,Map.of("status","ACTIVE")).status);
        assertEquals(200,call("GET","/api/seller/shop",a,null).status);
    }
    @Test @Order(5) void sellerRetainsCustomerCheckoutWithoutPaymentChanges()throws Exception{
        jdbc.update("INSERT INTO categories(name,slug,status,created_at,updated_at) VALUES('Test','test','ACTIVE',NOW(),NOW())");
        jdbc.update("INSERT INTO tea_regions(name,status,created_at,updated_at) VALUES('Test','ACTIVE',NOW(),NOW())");
        jdbc.update("INSERT INTO stores(name,status,created_at,updated_at) VALUES('Test','ACTIVE',NOW(),NOW())");
        jdbc.update("INSERT INTO products(category_id,region_id,store_id,name,slug,price,stock_quantity,weight_grams,status,created_at,updated_at) VALUES(1,1,1,'Test Tea','test-tea',100,20,200,'ACTIVE',NOW(),NOW())");
        assertEquals(200,call("POST","/api/cart/items",a,Map.of("productId",1,"quantity",1)).status);
        var session=call("POST","/api/checkout-sessions",a,Map.of("sourceType","CART","shipping",Map.of("recipientName","Synthetic","recipientPhone","0912345678","shippingAddress","Test")));
        assertEquals(201,session.status,session.raw);
        long sid=session.data.get("checkoutSessionId").longValue();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM orders",Integer.class)); // only legacy
        var order=call("POST","/api/checkout-sessions/"+sid+"/confirm-cod",a,null);
        assertEquals(200,order.status,order.raw);
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM orders",Integer.class));
        assertEquals("CUSTOMER",call("GET","/api/users/me",a,null).data.get("role").stringValue());
        assertEquals(200,call("GET","/api/orders",a,null).status);
    }
    @Test @Order(6) void googleLoginStillUsesCustomerAndReflectsSellerAuthority()throws Exception{
        jdbc.update("UPDATE users SET google_sub='seller-a-google' WHERE email='seller-a@example.invalid'");
        var challenge=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/auth/google/challenge"))
            .header("Origin","http://localhost:3000").POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,challenge.statusCode());
        String nonce=JSON.readTree(challenge.body()).get("nonce").stringValue();
        when(google.verify("synthetic-google-token",nonce)).thenReturn(new GoogleTokenVerifier.Identity("seller-a-google","seller-a@example.invalid","Synthetic Seller"));
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/auth/google"))
            .header("Origin","http://localhost:3000").header("Cookie",challenge.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0])
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"idToken\":\"synthetic-google-token\"}")).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,response.statusCode(),response.body());var body=JSON.readTree(response.body());
        assertEquals("CUSTOMER",body.get("user").get("role").stringValue());assertTrue(body.get("user").get("authorities").toString().contains("SELLER"));
        assertEquals(200,call("GET","/api/seller/shop",body.get("accessToken").stringValue(),null).status);
    }
    @Test @Order(7) void accountLockRevokesExistingJwtAndShopWrites()throws Exception{
        long owner=call("GET","/api/users/me",a,null).data.get("userId").longValue();
        assertEquals(200,call("PATCH","/api/admin/customers/"+owner+"/status",admin,Map.of("status","INACTIVE")).status);
        assertEquals(401,call("GET","/api/seller/shop",a,null).status);
        assertEquals(401,call("PATCH","/api/seller/shop",a,profile("blocked")).status);
        assertEquals(401,call("POST","/api/auth/login",null,Map.of("email","seller-a@example.invalid","password",PASSWORD)).status);
        assertEquals("A updated",jdbc.queryForObject("SELECT name FROM shops WHERE shop_id=?",String.class,shopA));
        assertEquals(200,call("PATCH","/api/admin/customers/"+owner+"/status",admin,Map.of("status","ACTIVE")).status);
    }
    @Test @Order(8) void databaseConstraintsAndAdminPagination()throws Exception{
        assertEquals(200,call("GET","/api/admin/shops?status=ACTIVE&size=1",admin,null).status);
        assertEquals(400,call("GET","/api/admin/shops?size=51",admin,null).status);
        assertEquals(400,call("GET","/api/admin/seller-applications?status=ADMIN",admin,null).status);
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO shops(owner_user_id,approved_application_id,name,representative_name,phone,business_address,description,status,status_changed_by,created_at,updated_at) SELECT owner_user_id,approved_application_id,name,representative_name,phone,business_address,description,status,status_changed_by,created_at,updated_at FROM shops WHERE shop_id=?",shopA));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id=1 AND amount=100 AND payment_status='PENDING'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role='SELLER'",Integer.class));
    }
    @Test @Order(9) void logoUploadValidatesBytesAndKeepsOwnerScope()throws Exception{
        var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);
        var buffer=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",buffer);byte[] png=buffer.toByteArray();
        String first="11111111-1111-1111-1111-111111111111.png",second="22222222-2222-2222-2222-222222222222.png";
        when(storage.stage(any(byte[].class),anyString())).thenReturn(first,second);
        when(storage.read(first)).thenReturn(png);
        var result=uploadLogo(a,png);
        assertEquals(200,result.statusCode(),result.body());
        String url=JSON.readTree(result.body()).get("logoUrl").stringValue();
        assertEquals("/api/shop-logos/"+first,url);
        assertTrue(call("GET","/api/seller/shop",b,null).data.get("logoUrl").isNull());
        var read=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+url)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,read.statusCode());assertEquals("image/png",read.headers().firstValue("Content-Type").orElseThrow());
        assertNotEquals(200,uploadLogo(a,"not an image".getBytes(java.nio.charset.StandardCharsets.UTF_8)).statusCode());
        assertEquals(200,uploadLogo(a,png).statusCode());
        verify(storage).deleteAfterCommit(first);
        assertEquals(404,call("GET",url,null,null).status);
        var suspended=call("PATCH","/api/admin/shops/"+shopA+"/status",admin,Map.of("status","SUSPENDED","reason","Logo test"));
        assertEquals(200,suspended.status);
        assertEquals(403,uploadLogo(a,png).statusCode());
        assertEquals(200,call("PATCH","/api/admin/shops/"+shopA+"/status",admin,Map.of("status","ACTIVE")).status);
    }
    HttpResponse<String> uploadLogo(String token,byte[] bytes)throws Exception{
        String boundary="SellerTestBoundary";
        var body=new java.io.ByteArrayOutputStream();
        body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"logo.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.write(bytes);body.write(("\r\n--"+boundary+"--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/seller/shop/logo"))
            .header("Authorization","Bearer "+token).header("Content-Type","multipart/form-data; boundary="+boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test @Order(10) void foundationCatalogAndOwnership()throws Exception {
        Long owner=jdbc.queryForObject("SELECT owner_user_id FROM shops WHERE shop_id=?",Long.class,shopA);
        Long other=jdbc.queryForObject("SELECT owner_user_id FROM shops WHERE shop_id=?",Long.class,shopB);
        assertThrows(RuntimeException.class,()->foundation.requireCatalogStore(owner));
        assertEquals(403,call("PATCH","/api/admin/shops/"+shopA+"/catalog-store",a,Map.of("storeId",1)).status);
        assertEquals(404,call("PATCH","/api/admin/shops/"+shopA+"/catalog-store",admin,Map.of("storeId",999999)).status);
        assertEquals(200,call("PATCH","/api/admin/shops/"+shopA+"/catalog-store",admin,Map.of("storeId",1)).status);
        assertEquals(1L,foundation.requireCatalogStore(owner));
        // Existing legacy rows remain inaccessible to both Sellers.
        assertThrows(vn.teasmart.backend.exception.ResourceNotFoundException.class,()->foundation.requireProduct(owner,1L));
        assertThrows(vn.teasmart.backend.exception.ResourceNotFoundException.class,()->foundation.requireOrder(owner,1L));
        jdbc.update("INSERT INTO products(category_id,region_id,store_id,shop_id,name,slug,price,stock_quantity,weight_grams,status,created_at,updated_at) VALUES(1,1,1,?,'Private test','private-test',100,10,200,'INACTIVE',NOW(),NOW())",shopA);
        Long product=jdbc.queryForObject("SELECT product_id FROM products WHERE slug='private-test'",Long.class);
        assertEquals(product,foundation.requireProduct(owner,product));
        assertThrows(vn.teasmart.backend.exception.ResourceNotFoundException.class,()->foundation.requireProduct(other,product));
        jdbc.update("INSERT INTO orders(order_code,user_id,shop_id,shop_name,recipient_name,recipient_phone,shipping_address,subtotal,shipping_fee,total_amount,order_status,created_at,updated_at) VALUES('SCOPED',?,?,'Snapshot','Test','0912345678','Test',100,0,100,'PENDING',NOW(),NOW())",owner,shopA);
        Long order=jdbc.queryForObject("SELECT order_id FROM orders WHERE order_code='SCOPED'",Long.class);
        assertEquals(order,foundation.requireOrder(owner,order));
        assertThrows(vn.teasmart.backend.exception.ResourceNotFoundException.class,()->foundation.requireOrder(other,order));
        assertEquals(409,call("PATCH","/api/admin/shops/"+shopA+"/catalog-store",admin,java.util.Collections.singletonMap("storeId",null)).status);
        assertEquals(200,call("PATCH","/api/admin/shops/"+shopA+"/status",admin,Map.of("status","SUSPENDED","reason","Foundation test")).status);
        assertThrows(RuntimeException.class,()->foundation.requireProduct(owner,product));
        assertEquals(200,call("PATCH","/api/admin/shops/"+shopA+"/status",admin,Map.of("status","ACTIVE")).status);
    }
    @Test @Order(11) void proposedStockDeltaSerializesWithReservation()throws Exception {
        Long product=jdbc.queryForObject("SELECT product_id FROM products WHERE slug='private-test'",Long.class);
        // Prototype only on synthetic DB; no stock-write API is exposed in step 1.
        var pool=Executors.newFixedThreadPool(2);
        try {
            var start=new CountDownLatch(1);
            var futures=new ArrayList<Future<Integer>>();
            for(int delta:List.of(-7,5)) futures.add(pool.submit(()->{
                start.await();try(var c=connection()) { c.setAutoCommit(false);
                    try(var s=c.prepareStatement("SELECT stock_quantity FROM products WHERE product_id=? FOR UPDATE")) {s.setLong(1,product);try(var r=s.executeQuery()){assertTrue(r.next());}}
                    try(var s=c.prepareStatement("UPDATE products SET stock_quantity=stock_quantity+? WHERE product_id=? AND stock_quantity+? BETWEEN 0 AND 4294967295")) {
                        s.setInt(1,delta);s.setLong(2,product);s.setInt(3,delta);int n=s.executeUpdate();c.commit();return n;
                    }
                }
            }));
            start.countDown();for(var f:futures)assertEquals(1,f.get(15,TimeUnit.SECONDS));
            assertEquals(8L,jdbc.queryForObject("SELECT stock_quantity FROM products WHERE product_id=?",Long.class,product));
            assertEquals(0,jdbc.update("UPDATE products SET stock_quantity=stock_quantity-9 WHERE product_id=? AND stock_quantity>=9",product));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE order_code='LEGACY' AND total_amount=100 AND shop_id IS NULL",Integer.class));
        } finally {pool.shutdownNow();}
    }
    @Test @Order(12) void sellerProductsAndIdempotentStock()throws Exception {
        var body=new HashMap<String,Object>(Map.of("categoryId",1,"regionId",1,"name","Seller product","description","Test","price",200,"weightGrams",200));
        assertEquals(409,call("POST","/api/seller/products",b,body).status); // Store unverified
        var created=call("POST","/api/seller/products",a,body);assertEquals(201,created.status,created.raw);long id=created.data.get("productId").longValue();
        assertEquals(0,created.data.get("stockQuantity").longValue());
        assertEquals(404,call("GET","/api/seller/products/"+id,b,null).status);
        assertEquals(404,call("PATCH","/api/seller/products/"+id,b,body).status);
        assertEquals(404,call("GET","/api/seller/products/1",a,null).status);
        body.put("shopId",shopB);assertEquals(400,call("PATCH","/api/seller/products/"+id,a,body).status);body.remove("shopId");
        body.put("price",-1);assertEquals(400,call("POST","/api/seller/products",a,body).status);body.put("price",250);body.put("name","Edited tea");
        assertEquals(200,call("PATCH","/api/seller/products/"+id,a,body).status);
        var list=call("GET","/api/seller/products?q=Edited&status=INACTIVE",a,null);assertEquals(1,list.data.get("totalElements").longValue());
        assertEquals(200,call("PATCH","/api/seller/products/"+id+"/status",a,Map.of("status","ACTIVE")).status);
        assertEquals("INACTIVE",jdbc.queryForObject("SELECT status FROM products WHERE product_id=?",String.class,id));
        String slug=jdbc.queryForObject("SELECT slug FROM products WHERE product_id=?",String.class,id);
        assertEquals(200,call("GET","/api/products/"+slug,null,null).status);
        assertEquals(409,call("PATCH","/api/admin/products/"+id+"/status",admin,Map.of("status","ACTIVE")).status);
        String key=UUID.randomUUID().toString();var pool=Executors.newFixedThreadPool(2);
        try {var one=pool.submit(()->adjustStock(id,key,10));var two=pool.submit(()->adjustStock(id,key,10));assertEquals(200,one.get().statusCode());assertEquals(200,two.get().statusCode());} finally{pool.shutdownNow();}
        assertEquals(10L,jdbc.queryForObject("SELECT stock_quantity FROM products WHERE product_id=?",Long.class,id));
        assertEquals(409,adjustStock(id,key,11).statusCode());
        assertEquals(409,adjustStock(id,UUID.randomUUID().toString(),-11).statusCode());
        assertEquals(200,adjustStock(id,UUID.randomUUID().toString(),-3).statusCode());
        assertEquals(7L,jdbc.queryForObject("SELECT stock_quantity FROM products WHERE product_id=?",Long.class,id));
        String buyer=login("plain@example.invalid");
        assertEquals(403,call("GET","/api/seller/products",buyer,null).status);
        var staleAdmin=new HashMap<String,Object>(body);
        staleAdmin.put("stockQuantity",99);staleAdmin.put("storeId",1);staleAdmin.put("status","INACTIVE");
        var checkout=Map.of("sourceType","BUY_NOW","buyNowProductId",id,"buyNowQuantity",1,
                "shipping",Map.of("recipientName","Test buyer","recipientPhone","0912345678","shippingAddress","Test"));
        int sessionsBefore=jdbc.queryForObject("SELECT COUNT(*) FROM checkout_sessions",Integer.class);
        var race=Executors.newFixedThreadPool(3);
        var start=new CountDownLatch(1);
        try {
            var seller=race.submit(()->{start.await();return adjustStock(id,UUID.randomUUID().toString(),2).statusCode();});
            var buyerAttempt=race.submit(()->{start.await();return call("POST","/api/checkout-sessions",buyer,checkout).status;});
            var adminAttempt=race.submit(()->{start.await();return call("PUT","/api/admin/products/"+id,admin,staleAdmin).status;});
            start.countDown();assertEquals(200,seller.get());assertEquals(201,buyerAttempt.get());assertEquals(409,adminAttempt.get());
        }finally{race.shutdownNow();}
        assertEquals(8L,jdbc.queryForObject("SELECT stock_quantity FROM products WHERE product_id=?",Long.class,id));
        assertEquals(sessionsBefore+1,jdbc.queryForObject("SELECT COUNT(*) FROM checkout_sessions",Integer.class));
        assertEquals(200,call("PATCH","/api/admin/shops/"+shopA+"/status",admin,Map.of("status","SUSPENDED","reason","Products test")).status);
        assertEquals(403,call("POST","/api/seller/products",a,body).status);
        assertEquals(200,call("PATCH","/api/admin/shops/"+shopA+"/status",admin,Map.of("status","ACTIVE")).status);
    }
    HttpResponse<String> adjustStock(long id,String key,long delta)throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/seller/products/"+id+"/stock-adjustments")).header("Authorization","Bearer "+a).header("Content-Type","application/json").header("Idempotency-Key",key).POST(HttpRequest.BodyPublishers.ofString("{\"delta\":"+delta+",\"reason\":\"Test stock\"}")).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test @Order(13) @EnabledIfEnvironmentVariable(named="SELLER_BROWSER_TEST",matches="true")
    void realBrowserUsesIsolatedSellerApi()throws Exception {
        var command=new ProcessBuilder("node","tests/seller-products.browser.mjs").directory(Path.of("..").toFile()).redirectErrorStream(true).redirectOutput(Path.of("target/seller-products-browser.log").toFile());
        command.environment().put("SELLER_TEST_PORT",Integer.toString(port));
        command.environment().put("SELLER_TEST_TOKEN",a);
        command.environment().put("SELLER_TEST_SCHEMA",DB);
        var process=command.start();
        try {assertTrue(process.waitFor(120,TimeUnit.SECONDS));assertEquals(0,process.exitValue());}
        finally {if(process.isAlive())process.destroyForcibly();}
    }

    @Test @Order(14) void oneShopSnapshotsAndSignedCallbacks()throws Exception {
        assertEquals(DB,jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertTrue(DB.matches("teasmart_seller_test_[a-f0-9]{32}"));
        // Publication simulation is confined to this disposable synthetic schema.
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='ck_seller_private_product'",Integer.class));
        try {
            long id=jdbc.queryForObject("SELECT product_id FROM products WHERE slug='private-test'",Long.class);
            jdbc.update("UPDATE products SET status='INACTIVE',seller_listing_status='ACTIVE',stock_quantity=100 WHERE product_id=?",id);
            long other=jdbc.queryForObject("SELECT product_id FROM products WHERE name='Edited tea'",Long.class);
            jdbc.update("UPDATE products SET shop_id=? WHERE product_id=?",shopB,other);
            var shipping=Map.of("recipientName","Test","recipientPhone","0912345678","shippingAddress","Test");
            long legacyStock=jdbc.queryForObject("SELECT stock_quantity FROM products WHERE product_id=1",Long.class);
            var adminEdit=new HashMap<String,Object>(Map.of("categoryId",1,"regionId",1,"storeId",1,"name","Test Tea","price",100,"weightGrams",200,"status","ACTIVE","stockQuantity",legacyStock,"expectedStockQuantity",legacyStock+1));
            assertEquals(409,call("PUT","/api/admin/products/1",admin,adminEdit).status);
            adminEdit.put("expectedStockQuantity",legacyStock);
            assertEquals(200,call("PUT","/api/admin/products/1",admin,adminEdit).status);

            for(long second:List.of(1L,other)) {
                int count=jdbc.queryForObject("SELECT COUNT(*) FROM checkout_sessions",Integer.class);
                var bad=call("POST","/api/checkout-sessions",a,Map.of("sourceType","BUY_AGAIN","buyAgainItems",List.of(Map.of("productId",id,"quantity",1),Map.of("productId",second,"quantity",1)),"shipping",shipping));
                assertEquals(409,bad.status,bad.raw);
                assertEquals("MULTI_SHOP_CHECKOUT",bad.data.get("code").stringValue());
                assertEquals(count,jdbc.queryForObject("SELECT COUNT(*) FROM checkout_sessions",Integer.class));
                assertEquals(100L,jdbc.queryForObject("SELECT stock_quantity FROM products WHERE product_id=?",Long.class,id));
            }
            assertEquals(200,call("POST","/api/cart/items",a,Map.of("productId",id,"quantity",1)).status);
            assertEquals(200,call("POST","/api/cart/items",a,Map.of("productId",1,"quantity",1)).status);
            var cart=call("GET","/api/cart",a,null).data.get("items");
            var selected=new ArrayList<Map<String,Object>>();
            for(var line:cart)selected.add(Map.of("cartItemId",line.get("cartItemId").longValue(),"quantity",line.get("quantity").longValue()));
            for(String source:List.of("CART","SELECTED")){
                var mixed=new HashMap<String,Object>();mixed.put("sourceType",source);mixed.put("shipping",shipping);
                if(source.equals("SELECTED"))mixed.put("selectedItems",selected);
                var rejected=call("POST","/api/checkout-sessions",a,mixed);
                assertEquals(409,rejected.status,rejected.raw);
                assertEquals("MULTI_SHOP_CHECKOUT",rejected.data.get("code").stringValue());
            }
            assertEquals(409,call("POST","/api/orders",a,shipping).status);
            assertEquals(409,call("POST","/api/orders/selected",a,Map.of("shipping",shipping,"items",selected)).status);
            for(var line:cart)assertEquals(204,call("DELETE","/api/cart/items/"+line.get("cartItemId").longValue(),a,null).status);
            var direct=call("POST","/api/orders/buy-now",a,Map.of("productId",id,"quantity",1,"shipping",shipping));
            assertEquals(201,direct.status,direct.raw);
            assertEquals(shopA,jdbc.queryForObject("SELECT shop_id FROM orders WHERE order_id=?",Long.class,direct.data.get("order").get("orderId").longValue()));
            var request=Map.of("sourceType","BUY_NOW","buyNowProductId",id,"buyNowQuantity",2,"shipping",shipping);
            long beforeRace=jdbc.queryForObject("SELECT stock_quantity FROM products WHERE product_id=?",Long.class,id);
            var workers=Executors.newFixedThreadPool(2);
            try {
                var checkout=workers.submit(()->call("POST","/api/checkout-sessions",a,request));
                var adjustment=workers.submit(()->adjustStock(id,UUID.randomUUID().toString(),5));
                assertEquals(201,checkout.get(15,TimeUnit.SECONDS).status);
                assertEquals(200,adjustment.get(15,TimeUnit.SECONDS).statusCode());
                assertEquals(beforeRace+3,jdbc.queryForObject("SELECT stock_quantity FROM products WHERE product_id=?",Long.class,id));
            } finally {workers.shutdownNow();}
            var created=call("POST","/api/checkout-sessions",a,request);assertEquals(201,created.status,created.raw);
            long sid=created.data.get("checkoutSessionId").longValue();
            assertEquals(shopA,created.data.get("shopId").longValue());
            String snapshot=created.data.get("shopName").stringValue();
            assertEquals(200,call("POST","/api/checkout-sessions/"+sid+"/confirm-cod",a,null).status);
            assertEquals(200,call("POST","/api/checkout-sessions/"+sid+"/confirm-cod",a,null).status);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=? AND shop_id=? AND total_amount=200",Integer.class,sid,shopA));
            for(String code:List.of("51","24","00")){
                // Each callback scenario is a separate buyer; UNKNOWN from 24 must stay intact.
                String email="callback-"+code+"@example.invalid";
                jdbc.update("INSERT INTO users(full_name,email,password_hash,role,status,created_at,updated_at) VALUES('Callback fixture',?,?,'CUSTOMER','ACTIVE',NOW(),NOW())",email,new BCryptPasswordEncoder().encode(PASSWORD));
                String callbackBuyer=login(email);
                created=call("POST","/api/checkout-sessions",callbackBuyer,request);assertEquals(201,created.status,created.raw);
                sid=created.data.get("checkoutSessionId").longValue();
                var attempt=call("POST","/api/checkout-sessions/"+sid+"/payment-attempts/vnpay",callbackBuyer,null);
                assertEquals(200,attempt.status,attempt.raw);
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?",Integer.class,sid));
                if(code.equals("00"))jdbc.update("UPDATE shops SET status='SUSPENDED',name='Changed' WHERE shop_id=?",shopA);
                var data=new TreeMap<String,String>();
                data.put("vnp_TmnCode","TESTONLY");data.put("vnp_TxnRef",attempt.data.get("attemptReference").stringValue());
                data.put("vnp_Amount","20000");data.put("vnp_ResponseCode",code);data.put("vnp_TransactionStatus",code.equals("00")?"00":"02");
                data.put("vnp_TransactionNo","991"+sid);
                data.put("vnp_PayDate",vn.teasmart.backend.service.VnpayGateway.DATE.format(vn.teasmart.backend.service.PaymentTime.now()));
                String query=vn.teasmart.backend.service.VnpayGateway.canonical(data);
                String path="/api/payments/vnpay/ipn?"+query+"&vnp_SecureHash="+gateway.sign(query);
                assertEquals(200,call("GET",path,null,null).status);assertEquals(200,call("GET",path,null,null).status);
                assertEquals(code.equals("00")?1:0,jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?",Integer.class,sid));
                if(code.equals("00"))assertEquals(snapshot,jdbc.queryForObject("SELECT shop_name FROM orders WHERE checkout_session_id=? AND shop_id=?",String.class,sid,shopA));
                if(code.equals("24"))assertEquals("HELD",jdbc.queryForObject("SELECT reservation_status FROM checkout_sessions WHERE checkout_session_id=?",String.class,sid));
                jdbc.update("UPDATE shops SET status='ACTIVE',name=? WHERE shop_id=?",snapshot,shopA);
            }
        }finally{
            jdbc.update("UPDATE products SET status='INACTIVE' WHERE shop_id IS NOT NULL");
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='ck_seller_private_product'",Integer.class));
        }
    }

    @Test @Order(15) void sellerOrdersAreShopScopedAndReuseAdminTransitions()throws Exception {
        assertEquals(DB,jdbc.queryForObject("SELECT DATABASE()",String.class));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM shops WHERE shop_id=?",String.class,shopA));
        long cod=createSellerOrder("SELLER-A-COD","PENDING",shopA,"COD","PENDING",null,null);
        long other=createSellerOrder("SELLER-B-COD","PENDING",shopB,"COD","PENDING",null,null);
        long legacy=createSellerOrder("LEGACY-COD","PENDING",null,"COD","PENDING",null,null);
        long online=createSellerOrder("SELLER-A-PAID","PENDING",shopA,"ONLINE","PAID","VNPAY","synthetic-vnpay-991");
        long uncertain=createSellerOrder("SELLER-A-UNKNOWN","PENDING",shopA,"ONLINE","PENDING","VNPAY",null);
        String plain=login("plain@example.invalid");

        var list=call("GET","/api/seller/orders?page=0&size=50",a,null);
        assertEquals(200,list.status,list.raw);
        var ids=new HashSet<Long>();for(var row:list.data.get("content"))ids.add(row.get("orderId").longValue());
        assertTrue(ids.contains(cod));assertTrue(ids.contains(online));assertTrue(ids.contains(uncertain));
        assertFalse(ids.contains(other));assertFalse(ids.contains(legacy));
        assertEquals(3,call("GET","/api/seller/orders?status=PENDING&q=SELLER-A-",a,null).data.get("totalElements").longValue());
        assertEquals(1,call("GET","/api/seller/orders?q=SELLER-A-PAID",a,null).data.get("totalElements").longValue());
        var sellerB=call("GET","/api/seller/orders?page=0&size=50",b,null);
        assertEquals(200,sellerB.status);assertTrue(sellerB.data.toString().contains("SELLER-B-COD"));assertFalse(sellerB.data.toString().contains("SELLER-A-COD"));
        assertEquals(403,call("GET","/api/seller/orders",plain,null).status);
        assertEquals(404,call("GET","/api/seller/orders/"+other,a,null).status);
        assertEquals(404,call("GET","/api/seller/orders/"+legacy,a,null).status);

        var detail=call("GET","/api/seller/orders/"+cod,a,null);
        assertEquals(200,detail.status,detail.raw);assertEquals("COD",detail.data.get("paymentMethod").stringValue());
        assertEquals("PENDING",detail.data.get("paymentStatus").stringValue());
        assertEquals(300,detail.data.get("totalAmount").intValue());assertEquals(1,detail.data.get("items").size());
        assertFalse(detail.raw.contains("email"));assertFalse(detail.raw.contains("userId"));
        long stockBefore=jdbc.queryForObject("SELECT stock_quantity FROM products WHERE product_id=1",Long.class);
        String sessionsBefore=jdbc.query("SELECT * FROM checkout_sessions ORDER BY checkout_session_id",SellerMysqlTest::snapshot);
        String attemptsBefore=jdbc.query("SELECT * FROM payment_attempts ORDER BY payment_attempt_id",SellerMysqlTest::snapshot);
        assertEquals(409,call("PATCH","/api/seller/orders/"+cod+"/status",a,Map.of("status","SHIPPING")).status);

        var parallel=Executors.newFixedThreadPool(2);
        try {
            var jobs=parallel.invokeAll(List.<Callable<Result>>of(
                ()->call("PATCH","/api/seller/orders/"+cod+"/status",a,Map.of("status","CONFIRMED")),
                ()->call("PATCH","/api/seller/orders/"+cod+"/status",a,Map.of("status","CONFIRMED"))));
            var codes=new ArrayList<Integer>();for(var job:jobs)codes.add(job.get().status);Collections.sort(codes);
            assertEquals(List.of(200,409),codes);
        } finally {parallel.shutdownNow();}
        assertEquals(200,call("PATCH","/api/seller/orders/"+cod+"/status",a,Map.of("status","SHIPPING")).status);
        var delivered=call("PATCH","/api/seller/orders/"+cod+"/status",a,Map.of("status","DELIVERED"));
        assertEquals(200,delivered.status,delivered.raw);assertEquals("PENDING",delivered.data.get("paymentStatus").stringValue());
        assertEquals(400,call("PATCH","/api/seller/orders/"+cod+"/status",a,Map.of("status","PAID")).status);
        assertEquals(409,call("PATCH","/api/seller/orders/"+uncertain+"/status",a,Map.of("status","CONFIRMED")).status);
        assertEquals(200,call("PATCH","/api/seller/orders/"+online+"/status",a,Map.of("status","CONFIRMED")).status);
        assertEquals("PAID",jdbc.queryForObject("SELECT payment_status FROM payments WHERE order_id=?",String.class,online));
        assertEquals("synthetic-vnpay-991",jdbc.queryForObject("SELECT transaction_code FROM payments WHERE order_id=?",String.class,online));
        assertEquals(new java.math.BigDecimal("300.00"),jdbc.queryForObject("SELECT total_amount FROM orders WHERE order_id=?",java.math.BigDecimal.class,cod));
        assertEquals("PENDING",jdbc.queryForObject("SELECT payment_status FROM payments WHERE order_id=?",String.class,cod));
        assertEquals(stockBefore,jdbc.queryForObject("SELECT stock_quantity FROM products WHERE product_id=1",Long.class));
        assertEquals(sessionsBefore,jdbc.query("SELECT * FROM checkout_sessions ORDER BY checkout_session_id",SellerMysqlTest::snapshot));
        assertEquals(attemptsBefore,jdbc.query("SELECT * FROM payment_attempts ORDER BY payment_attempt_id",SellerMysqlTest::snapshot));
        assertTrue(call("GET","/api/admin/orders",admin,null).status==200);
    }

    private long createSellerOrder(String code,String status,Long shop,String method,String paymentStatus,String gateway,String transactionCode) {
        jdbc.update("INSERT INTO orders(order_code,user_id,recipient_name,recipient_phone,shipping_address,subtotal,shipping_fee,total_amount,order_status,note,created_at,updated_at,shop_id,shop_name) VALUES(?,(SELECT user_id FROM users WHERE email='plain@example.invalid'),'Synthetic Buyer','0912345678','Synthetic Test Address',300,0,300,?,'Seller order fixture',NOW(),NOW(),?,?)",code,status,shop,shop==null?null:(shop==shopA?"Seller A":"Seller B"));
        long id=jdbc.queryForObject("SELECT order_id FROM orders WHERE order_code=?",Long.class,code);
        jdbc.update("INSERT INTO order_items(order_id,product_id,product_name,unit_price,quantity,subtotal) VALUES(?,1,'Tea Test',300,1,300)",id);
        jdbc.update("INSERT INTO payments(order_id,payment_method,amount,payment_status,transaction_code,paid_at,created_at,updated_at,gateway,merchant_reference,reconciliation_required) VALUES(?,?,300,?,?,?,NOW(),NOW(),?,?,FALSE)",id,method,paymentStatus,transactionCode,"PAID".equals(paymentStatus)?java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()):null,gateway,"ONLINE".equals(method)?"fixture-"+code:null);
        return id;
    }

    @Test @Order(16) @EnabledIfEnvironmentVariable(named="SELLER_BROWSER_TEST",matches="true")
    void sellerOrdersRendersAtDesktopTabletAndMobileWidths()throws Exception {
        createSellerOrder("BROWSER-SELLER-ORDERS","PENDING",shopA,"COD","PENDING",null,null);
        var command=new ProcessBuilder("node","tests/seller-orders.browser.mjs").directory(Path.of("..").toFile()).redirectErrorStream(true).redirectOutput(Path.of("target/seller-orders-browser.log").toFile());
        command.environment().put("SELLER_TEST_PORT",Integer.toString(port));
        command.environment().put("SELLER_TEST_TOKEN",a);
        command.environment().put("SELLER_TEST_SCHEMA",DB);
        var process=command.start();
        try {assertTrue(process.waitFor(90,TimeUnit.SECONDS));assertEquals(0,process.exitValue());}
        finally {if(process.isAlive())process.destroyForcibly();}
    }

    @Test @Order(17) void sellerListingCustomerCodCheckoutOrderHandlingAndAdminStatisticsEndToEnd()throws Exception {
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT status FROM shops WHERE shop_id=?",String.class,shopA));
        String buyer=login("plain@example.invalid");
        String today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toString();
        var before=call("GET","/api/admin/dashboard?from="+today+"&to="+today,admin,null);
        assertEquals(200,before.status,before.raw);

        var productBody=new HashMap<String,Object>(Map.of("categoryId",1,"regionId",1,"name","E2E seller tea","description","Synthetic end-to-end product","price",125,"weightGrams",100,"imageUrl","/placeholder-teasmart.svg"));
        var created=call("POST","/api/seller/products",a,productBody);
        assertEquals(201,created.status,created.raw);long productId=created.data.get("productId").longValue();
        productBody.put("price",150);productBody.put("name","E2E seller tea updated");
        assertEquals(200,call("PATCH","/api/seller/products/"+productId,a,productBody).status);
        assertEquals(200,adjustStock(productId,UUID.randomUUID().toString(),5).statusCode());
        assertEquals(200,call("PATCH","/api/seller/products/"+productId+"/status",a,Map.of("status","ACTIVE")).status);
        assertEquals("INACTIVE",jdbc.queryForObject("SELECT status FROM products WHERE product_id=?",String.class,productId));
        assertEquals("ACTIVE",jdbc.queryForObject("SELECT seller_listing_status FROM products WHERE product_id=?",String.class,productId));

        String slug=jdbc.queryForObject("SELECT slug FROM products WHERE product_id=?",String.class,productId);
        var catalog=call("GET","/api/products?keyword=E2E%20seller%20tea%20updated",null,null);
        assertEquals(200,catalog.status,catalog.raw);assertEquals(1,catalog.data.get("totalElements").longValue());
        assertEquals(productId,catalog.data.get("content").get(0).get("productId").longValue());
        var publicDetail=call("GET","/api/products/"+slug,null,null);
        assertEquals(200,publicDetail.status,publicDetail.raw);assertEquals("/placeholder-teasmart.svg",publicDetail.data.get("imageUrl").stringValue());
        assertEquals(403,call("GET","/api/seller/products",buyer,null).status);
        assertEquals(403,call("GET","/api/admin/orders",a,null).status);

        assertEquals(200,call("POST","/api/cart/items",buyer,Map.of("productId",productId,"quantity",1)).status);
        var session=call("POST","/api/checkout-sessions",buyer,Map.of("sourceType","CART","shipping",Map.of("recipientName","E2E Buyer","recipientPhone","0912345678","shippingAddress","Synthetic customer address")));
        assertEquals(201,session.status,session.raw);long sessionId=session.data.get("checkoutSessionId").longValue();
        assertEquals(shopA,session.data.get("shopId").longValue());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?",Integer.class,sessionId));
        var confirmed=call("POST","/api/checkout-sessions/"+sessionId+"/confirm-cod",buyer,null);
        assertEquals(200,confirmed.status,confirmed.raw);long orderId=confirmed.data.get("orderId").longValue();
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE order_id=? AND shop_id=? AND total_amount=150",Integer.class,orderId,shopA));
        assertEquals("PENDING",jdbc.queryForObject("SELECT payment_status FROM payments WHERE order_id=?",String.class,orderId));
        assertEquals(4L,jdbc.queryForObject("SELECT stock_quantity FROM products WHERE product_id=?",Long.class,productId));

        var sellerList=call("GET","/api/seller/orders?q="+jdbc.queryForObject("SELECT order_code FROM orders WHERE order_id=?",String.class,orderId),a,null);
        assertEquals(200,sellerList.status,sellerList.raw);assertEquals(1,sellerList.data.get("totalElements").longValue());
        assertEquals(404,call("GET","/api/seller/orders/"+orderId,b,null).status);
        for(String next:List.of("CONFIRMED","SHIPPING","DELIVERED"))assertEquals(200,call("PATCH","/api/seller/orders/"+orderId+"/status",a,Map.of("status",next)).status);
        assertEquals("PENDING",jdbc.queryForObject("SELECT payment_status FROM payments WHERE order_id=?",String.class,orderId));
        assertEquals("DELIVERED",call("GET","/api/admin/orders/"+orderId,admin,null).data.get("orderStatus").stringValue());
        assertEquals("DELIVERED",call("GET","/api/orders/"+orderId,buyer,null).data.get("orderStatus").stringValue());

        var after=call("GET","/api/admin/dashboard?from="+today+"&to="+today,admin,null);
        assertEquals(200,after.status,after.raw);
        assertEquals(before.data.get("ordersCount").longValue()+1,after.data.get("ordersCount").longValue());
        assertEquals(before.data.get("productsCount").longValue()+1,after.data.get("productsCount").longValue());
        assertEquals(before.data.get("customersCount").longValue(),after.data.get("customersCount").longValue());
        assertEquals(before.data.get("revenue").decimalValue(),after.data.get("revenue").decimalValue());
        assertEquals(before.data.get("ordersByStatus").get("DELIVERED").longValue()+1,
                after.data.get("ordersByStatus").get("DELIVERED").longValue());
    }

    @Test @Order(18) void sellerEligibilityRejectsEverySessionEntryPointWithConstraintIntact() throws Exception {
        long id=jdbc.queryForObject("SELECT product_id FROM products WHERE slug='private-test'",Long.class);
        var shipping=Map.of("recipientName","Test","recipientPhone","0912345678","shippingAddress","Test");
        var oldCart=call("GET","/api/cart",a,null).data.get("items");
        for(var line:oldCart)call("DELETE","/api/cart/items/"+line.get("cartItemId").longValue(),a,null);
        jdbc.update("UPDATE products SET stock_quantity=100 WHERE product_id=?",id);
        assertEquals(200,call("POST","/api/cart/items",a,Map.of("productId",id,"quantity",1)).status);
        var cart=call("GET","/api/cart",a,null).data.get("items");
        var selected=List.of(Map.of("cartItemId",cart.get(0).get("cartItemId").longValue(),"quantity",1));
        var requests=List.of(
            Map.of("sourceType","CART","shipping",shipping),
            Map.of("sourceType","SELECTED","selectedItems",selected,"shipping",shipping),
            Map.of("sourceType","BUY_NOW","buyNowProductId",id,"buyNowQuantity",1,"shipping",shipping),
            Map.of("sourceType","BUY_AGAIN","buyAgainItems",List.of(Map.of("productId",id,"quantity",1)),"shipping",shipping));
        long category=jdbc.queryForObject("SELECT category_id FROM products WHERE product_id=?",Long.class,id);
        long region=jdbc.queryForObject("SELECT region_id FROM products WHERE product_id=?",Long.class,id);
        String[] bad={"UPDATE products SET seller_listing_status='INACTIVE' WHERE product_id="+id,
            "UPDATE shops SET status='SUSPENDED' WHERE shop_id="+shopA,
            "UPDATE shops SET catalog_verified_at=NULL WHERE shop_id="+shopA,
            "UPDATE categories SET status='INACTIVE' WHERE category_id="+category,
            "UPDATE tea_regions SET status='INACTIVE' WHERE region_id="+region,
            "UPDATE products SET stock_quantity=0 WHERE product_id="+id,
            "UPDATE products SET price=0 WHERE product_id="+id};
        String[] restore={"UPDATE products SET seller_listing_status='ACTIVE' WHERE product_id="+id,
            "UPDATE shops SET status='ACTIVE' WHERE shop_id="+shopA,
            "UPDATE shops SET catalog_verified_at=NOW() WHERE shop_id="+shopA,
            "UPDATE categories SET status='ACTIVE' WHERE category_id="+category,
            "UPDATE tea_regions SET status='ACTIVE' WHERE region_id="+region,
            "UPDATE products SET stock_quantity=100 WHERE product_id="+id,
            "UPDATE products SET price=100 WHERE product_id="+id};
        for(int i=0;i<bad.length;i++) {
            jdbc.update(bad[i]);
            try {
                for(var request:requests){var result=call("POST","/api/checkout-sessions",a,request);assertTrue(result.status>=400 && result.status<500,result.raw);}
                for(String endpoint:List.of("/api/orders/buy-now","/api/orders/buy-now/vnpay")) {
                    var result=call("POST",endpoint,a,Map.of("productId",id,"quantity",1,"shipping",shipping));
                    assertTrue(result.status>=400 && result.status<500,result.raw);
                }
                var full=call("POST","/api/orders",a,shipping);assertTrue(full.status>=400 && full.status<500,full.raw);
                var part=call("POST","/api/orders/selected",a,Map.of("shipping",shipping,"items",selected));assertTrue(part.status>=400 && part.status<500,part.raw);
            } finally {jdbc.update(restore[i]);}
        }
        for(var request:requests){var result=call("POST","/api/checkout-sessions",a,request);assertEquals(201,result.status,result.raw);}
        assertEquals("INACTIVE",jdbc.queryForObject("SELECT status FROM products WHERE product_id=?",String.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND CONSTRAINT_NAME='ck_seller_private_product'",Integer.class));
    }
    @Test @Order(19) @EnabledIfEnvironmentVariable(named="SELLER_BROWSER_TEST",matches="true")
    void profileAndRegistrationUsePersistedDetails()throws Exception {
        String token=login("plain@example.invalid");
        var command=new ProcessBuilder("node","tests/seller-registration.browser.mjs").directory(Path.of("..").toFile()).inheritIO();
        command.environment().put("SELLER_TEST_PORT",Integer.toString(port));command.environment().put("SELLER_TEST_TOKEN",token);command.environment().put("SELLER_TEST_SCHEMA",DB);
        var process=command.start();try{assertTrue(process.waitFor(180,TimeUnit.SECONDS));assertEquals(0,process.exitValue());}finally{if(process.isAlive())process.destroyForcibly();}
        var application=call("GET","/api/seller-applications/me",token,null).data.get("application");
        assertEquals("COOPERATIVE",application.get("businessType").stringValue());assertEquals("Tỉnh Thái Nguyên",application.get("province").stringValue());assertEquals("plain@example.invalid",application.get("representativeEmail").stringValue());
        var adminView=call("GET","/api/admin/seller-applications/"+application.get("applicationId").longValue(),admin,null);
        assertEquals("Xã Tân Cương",adminView.data.get("ward").stringValue());
    }
    @Test @Order(20) @EnabledIfEnvironmentVariable(named="SELLER_BROWSER_TEST",matches="true")
    void adminSellerDrawerFiltersAndRejectsSyntheticApplication()throws Exception {
        var capabilities=call("GET","/api/admin/seller-capabilities",admin,null);assertEquals(200,capabilities.status,capabilities.raw);assertTrue(capabilities.data.get("profileDetailsEnabled").booleanValue());assertEquals(403,call("GET","/api/admin/seller-capabilities",a,null).status);
        var command=new ProcessBuilder("node","tests/admin-sellers.browser.mjs").directory(Path.of("..").toFile()).redirectErrorStream(true).redirectOutput(Path.of("target/admin-sellers-browser.log").toFile());
        command.environment().put("SELLER_TEST_PORT",Integer.toString(port));command.environment().put("SELLER_TEST_TOKEN",admin);command.environment().put("SELLER_TEST_SCHEMA",DB);
        var process=command.start();try{assertTrue(process.waitFor(180,TimeUnit.SECONDS));assertEquals(0,process.exitValue());}finally{if(process.isAlive())process.destroyForcibly();}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM seller_applications WHERE shop_name='Gian hàng kiểm thử giao diện' AND status='REJECTED' AND rejection_reason IS NOT NULL",Integer.class));
    }

    @Test @Order(21) void sellerStatisticsAreShopScopedAndSeparatePaidFromDeliveredCod()throws Exception {
        jdbc.update("UPDATE shops SET name='Trà Thái Nguyên — Gian hàng A' WHERE shop_id=?",shopA);
        jdbc.update("UPDATE shops SET name='Không được lộ gian hàng B' WHERE shop_id=?",shopB);
        long paid=createSellerOrder("STATS-A-PAID","DELIVERED",shopA,"ONLINE","PAID","VNPAY","stats-a-paid");
        long cod=createSellerOrder("STATS-A-COD","DELIVERED",shopA,"COD","PENDING",null,null);
        long pending=createSellerOrder("STATS-A-PENDING","PENDING",shopA,"COD","PENDING",null,null);
        long cancelled=createSellerOrder("STATS-A-CANCELLED","CANCELLED",shopA,"COD","PENDING",null,null);
        long other=createSellerOrder("STATS-B-PAID","DELIVERED",shopB,"ONLINE","PAID","VNPAY","stats-b-paid");
        long legacy=createSellerOrder("STATS-LEGACY","DELIVERED",null,"COD","PAID",null,null);
        long late=createSellerOrder("STATS-A-LATE-PAID","DELIVERED",shopA,"ONLINE","PAID","VNPAY","stats-a-late");
        for(long id:List.of(paid,cod,pending,cancelled,other,legacy,late)){
            jdbc.update("UPDATE orders SET created_at='2001-01-02 10:00:00' WHERE order_id=?",id);
            jdbc.update("UPDATE payments SET paid_at='2001-01-02 11:00:00' WHERE order_id=? AND payment_status='PAID'",id);
        }
        jdbc.update("UPDATE orders SET created_at='2000-12-31 10:00:00' WHERE order_id=?",late);
        jdbc.update("UPDATE order_items SET product_name='=1+1' WHERE order_id IN (?,?)",paid,cod);
        String attempts=jdbc.query("SELECT * FROM payment_attempts ORDER BY payment_attempt_id",SellerMysqlTest::snapshot);
        String sessions=jdbc.query("SELECT * FROM checkout_sessions ORDER BY checkout_session_id",SellerMysqlTest::snapshot);
        String path="/api/seller/statistics?from=2001-01-01&to=2001-01-03";
        var report=call("GET",path,a,null);assertEquals(200,report.status,report.raw);var d=report.data;
        assertEquals(shopA,d.get("shopId").longValue());assertEquals(600,d.get("revenue").intValue());assertEquals(2,d.get("paidPayments").intValue());
        assertEquals(4,d.get("ordersCount").intValue());assertEquals(900,d.get("totalOrderValue").intValue());assertEquals(600,d.get("unpaidOrderValue").intValue());
        assertEquals(2,d.get("itemsSold").intValue());assertEquals(1,d.get("topProducts").size());assertEquals(2,d.get("topProducts").get(0).get("quantity").intValue());assertEquals("=1+1",d.get("topProducts").get(0).get("name").stringValue());
        assertEquals(3,d.get("revenueByDay").size());assertEquals(0,d.get("revenueByDay").get(0).get("revenue").intValue());assertEquals(600,d.get("revenueByDay").get(1).get("revenue").intValue());
        assertFalse(report.raw.contains("Không được lộ"));assertEquals(shopB,call("GET",path,b,null).data.get("shopId").longValue());assertEquals(300,call("GET",path,b,null).data.get("revenue").intValue());
        assertEquals(400,call("GET",path+"&shopId="+shopB,a,null).status);assertEquals(403,call("GET",path,login("plain@example.invalid"),null).status);
        assertEquals(400,call("GET","/api/seller/statistics?from=2001-02-01&to=2001-01-01",a,null).status);
        assertEquals(400,call("GET","/api/seller/statistics?from=2000-01-01&to=2001-01-01",a,null).status);
        var empty=call("GET","/api/seller/statistics?from=2002-01-01&to=2002-01-03",a,null);assertEquals(200,empty.status);assertEquals(0,empty.data.get("revenue").intValue());assertEquals(0,empty.data.get("ordersCount").intValue());assertEquals(0,empty.data.get("topProducts").size());
        jdbc.update("UPDATE shops SET status='SUSPENDED' WHERE shop_id=?",shopA);try{assertEquals(403,call("GET",path,a,null).status);}finally{jdbc.update("UPDATE shops SET status='ACTIVE' WHERE shop_id=?",shopA);}
        assertEquals(attempts,jdbc.query("SELECT * FROM payment_attempts ORDER BY payment_attempt_id",SellerMysqlTest::snapshot));assertEquals(sessions,jdbc.query("SELECT * FROM checkout_sessions ORDER BY checkout_session_id",SellerMysqlTest::snapshot));
    }
    @Test @Order(22) @EnabledIfEnvironmentVariable(named="SELLER_BROWSER_TEST",matches="true")
    void sellerStatisticsBrowserAndExportsUseRealScopedData()throws Exception {
        var command=new ProcessBuilder("node","tests/seller-statistics.browser.mjs").directory(Path.of("..").toFile()).redirectErrorStream(true).redirectOutput(Path.of("target/seller-statistics-browser.log").toFile());
        command.environment().put("SELLER_TEST_PORT",Integer.toString(port));command.environment().put("SELLER_TEST_TOKEN",a);command.environment().put("SELLER_TEST_SCHEMA",DB);command.environment().put("SELLER_TEST_SHOP",Long.toString(shopA));
        var process=command.start();try{assertTrue(process.waitFor(180,TimeUnit.SECONDS));assertEquals(0,process.exitValue());}finally{if(process.isAlive())process.destroyForcibly();}
    }


    @Test @Order(23) void archivePreservesHistoryAndHeldCheckoutsAndRejectsNewPurchases()throws Exception {
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME='products' AND CONSTRAINT_NAME='ck_seller_product_archive'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='products' AND INDEX_NAME='ix_seller_product_archive'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE shop_id IS NULL AND seller_archived_at IS NOT NULL",Integer.class));
        var body=Map.of("categoryId",1,"regionId",1,"name","Archive history product","description","Synthetic archive test","price",125,"weightGrams",100);
        var created=call("POST","/api/seller/products",a,body);assertEquals(201,created.status,created.raw);long id=created.data.get("productId").longValue();String key=UUID.randomUUID().toString();assertEquals(200,adjustStock(id,key,8).statusCode());
        assertEquals(200,call("PATCH","/api/seller/products/"+id+"/status",a,Map.of("status","ACTIVE")).status);
        String slug=jdbc.queryForObject("SELECT slug FROM products WHERE product_id=?",String.class,id);assertEquals(200,call("GET","/api/products/"+slug,null,null).status);
        String buyer=login("plain@example.invalid");var shipping=Map.of("recipientName","Synthetic Buyer","recipientPhone","0912345678","shippingAddress","Synthetic test address");
        for(var line:call("GET","/api/cart",buyer,null).data.get("items"))call("DELETE","/api/cart/items/"+line.get("cartItemId").longValue(),buyer,null);
        assertEquals(200,call("POST","/api/cart/items",buyer,Map.of("productId",id,"quantity",1)).status);
        var cart=call("GET","/api/cart",buyer,null).data.get("items");var selected=List.of(Map.of("cartItemId",cart.get(0).get("cartItemId").longValue(),"quantity",1));
        var processing=call("POST","/api/orders/buy-now",buyer,Map.of("productId",id,"quantity",1,"shipping",shipping));assertEquals(201,processing.status,processing.raw);long processingId=processing.data.get("order").get("orderId").longValue();
        var request=Map.of("sourceType","BUY_NOW","buyNowProductId",id,"buyNowQuantity",1,"shipping",shipping);
        var vnpay=call("POST","/api/checkout-sessions",buyer,request);assertEquals(201,vnpay.status,vnpay.raw);long vnpayId=vnpay.data.get("checkoutSessionId").longValue();
        var cod=call("POST","/api/checkout-sessions",buyer,request);assertEquals(201,cod.status,cod.raw);long codId=cod.data.get("checkoutSessionId").longValue();
        var cancel=call("POST","/api/checkout-sessions",buyer,request);assertEquals(201,cancel.status,cancel.raw);long cancelId=cancel.data.get("checkoutSessionId").longValue();
        // Prepare existing READY sessions before opening a PENDING attempt.
        var attempt=call("POST","/api/checkout-sessions/"+vnpayId+"/payment-attempts/vnpay",buyer,null);assertEquals(200,attempt.status,attempt.raw);
        long stock=jdbc.queryForObject("SELECT stock_quantity FROM products WHERE product_id=?",Long.class,id);
        assertEquals(404,call("POST","/api/seller/products/"+id+"/archive",b,null).status);assertEquals(403,call("POST","/api/seller/products/"+id+"/archive",buyer,null).status);assertEquals(404,call("POST","/api/seller/products/1/archive",a,null).status);
        jdbc.update("UPDATE shops SET status='SUSPENDED' WHERE shop_id=?",shopA);try{assertEquals(403,call("POST","/api/seller/products/"+id+"/archive",a,null).status);}finally{jdbc.update("UPDATE shops SET status='ACTIVE' WHERE shop_id=?",shopA);}
        String sessions=jdbc.query("SELECT * FROM checkout_sessions ORDER BY checkout_session_id",SellerMysqlTest::snapshot),attempts=jdbc.query("SELECT * FROM payment_attempts ORDER BY payment_attempt_id",SellerMysqlTest::snapshot),orders=jdbc.query("SELECT * FROM orders ORDER BY order_id",SellerMysqlTest::snapshot),items=jdbc.query("SELECT * FROM order_items ORDER BY order_item_id",SellerMysqlTest::snapshot),payments=jdbc.query("SELECT * FROM payments ORDER BY payment_id",SellerMysqlTest::snapshot);
        String stats=call("GET","/api/seller/statistics",a,null).raw;
        var archived=call("POST","/api/seller/products/"+id+"/archive",a,null);assertEquals(200,archived.status,archived.raw);assertEquals("ARCHIVED",archived.data.get("status").stringValue());assertFalse(archived.data.get("archivedAt").isNull());
        var replay=call("POST","/api/seller/products/"+id+"/archive",a,null);assertEquals(200,replay.status);assertEquals(archived.raw,replay.raw);
        assertEquals(stock,jdbc.queryForObject("SELECT stock_quantity FROM products WHERE product_id=?",Long.class,id));assertEquals("INACTIVE",jdbc.queryForObject("SELECT status FROM products WHERE product_id=?",String.class,id));assertEquals("INACTIVE",jdbc.queryForObject("SELECT seller_listing_status FROM products WHERE product_id=?",String.class,id));
        assertEquals(jdbc.queryForObject("SELECT owner_user_id FROM shops WHERE shop_id=?",Long.class,shopA),jdbc.queryForObject("SELECT seller_archived_by FROM products WHERE product_id=?",Long.class,id));
        assertEquals(sessions,jdbc.query("SELECT * FROM checkout_sessions ORDER BY checkout_session_id",SellerMysqlTest::snapshot));assertEquals(attempts,jdbc.query("SELECT * FROM payment_attempts ORDER BY payment_attempt_id",SellerMysqlTest::snapshot));assertEquals(orders,jdbc.query("SELECT * FROM orders ORDER BY order_id",SellerMysqlTest::snapshot));assertEquals(items,jdbc.query("SELECT * FROM order_items ORDER BY order_item_id",SellerMysqlTest::snapshot));assertEquals(payments,jdbc.query("SELECT * FROM payments ORDER BY payment_id",SellerMysqlTest::snapshot));assertEquals(stats,call("GET","/api/seller/statistics",a,null).raw);
        assertEquals(1,call("GET","/api/seller/products?q=Archive%20history&status=ARCHIVED",a,null).data.get("totalElements").intValue());assertEquals(0,call("GET","/api/seller/products?q=Archive%20history&status=INACTIVE",a,null).data.get("totalElements").intValue());assertEquals(0,call("GET","/api/seller/products?q=Archive%20history&status=ACTIVE",a,null).data.get("totalElements").intValue());
        assertEquals(409,call("PATCH","/api/seller/products/"+id,a,body).status);assertEquals(409,call("PATCH","/api/seller/products/"+id+"/status",a,Map.of("status","ACTIVE")).status);assertEquals(409,adjustStock(id,UUID.randomUUID().toString(),1).statusCode());assertEquals(200,adjustStock(id,key,8).statusCode());assertEquals(stock,jdbc.queryForObject("SELECT stock_quantity FROM products WHERE product_id=?",Long.class,id));
        var violation=assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("UPDATE products SET seller_listing_status='ACTIVE' WHERE product_id=?",id));var sql=assertInstanceOf(java.sql.SQLException.class,violation.getMostSpecificCause());assertEquals(3819,sql.getErrorCode());assertTrue(sql.getMessage().contains("ck_seller_product_archive"));
        assertEquals(404,call("GET","/api/products/"+slug,null,null).status);var unavailable=call("GET","/api/cart",buyer,null).data.get("items").get(0);assertFalse(unavailable.get("available").booleanValue());assertEquals("PRODUCT_UNAVAILABLE",unavailable.get("availabilityReason").stringValue());assertEquals(409,call("POST","/api/cart/items",buyer,Map.of("productId",id,"quantity",1)).status);
        for(var invalid:List.of(Map.of("sourceType","CART","shipping",shipping),Map.of("sourceType","SELECTED","selectedItems",selected,"shipping",shipping),request,Map.of("sourceType","BUY_AGAIN","buyAgainItems",List.of(Map.of("productId",id,"quantity",1)),"shipping",shipping))){var response=call("POST","/api/checkout-sessions",buyer,invalid);assertEquals(409,response.status,response.raw);}
        assertEquals(409,call("POST","/api/orders",buyer,shipping).status);assertEquals(409,call("POST","/api/orders/selected",buyer,Map.of("shipping",shipping,"items",selected)).status);assertEquals(409,call("POST","/api/orders/buy-now",buyer,Map.of("productId",id,"quantity",1,"shipping",shipping)).status);
        assertEquals(200,call("GET","/api/seller/orders/"+processingId,a,null).status);assertEquals(200,call("PATCH","/api/seller/orders/"+processingId+"/status",a,Map.of("status","CONFIRMED")).status);
        assertEquals(200,call("POST","/api/checkout-sessions/"+codId+"/confirm-cod",buyer,null).status);assertEquals(200,call("POST","/api/checkout-sessions/"+codId+"/confirm-cod",buyer,null).status);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?",Integer.class,codId));
        var data=new TreeMap<String,String>();data.put("vnp_TmnCode","TESTONLY");data.put("vnp_TxnRef",attempt.data.get("attemptReference").stringValue());data.put("vnp_Amount","12500");data.put("vnp_ResponseCode","00");data.put("vnp_TransactionStatus","00");data.put("vnp_TransactionNo","998"+vnpayId);data.put("vnp_PayDate",vn.teasmart.backend.service.VnpayGateway.DATE.format(vn.teasmart.backend.service.PaymentTime.now()));String canonical=vn.teasmart.backend.service.VnpayGateway.canonical(data);String callback="/api/payments/vnpay/ipn?"+canonical+"&vnp_SecureHash="+gateway.sign(canonical);assertEquals(200,call("GET",callback,null,null).status);assertEquals(200,call("GET",callback,null,null).status);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE checkout_session_id=?",Integer.class,vnpayId));assertEquals(stock,jdbc.queryForObject("SELECT stock_quantity FROM products WHERE product_id=?",Long.class,id));
        assertEquals(200,call("POST","/api/checkout-sessions/"+cancelId+"/cancel",buyer,null).status);assertEquals(200,call("POST","/api/checkout-sessions/"+cancelId+"/cancel",buyer,null).status);assertEquals(stock+1,jdbc.queryForObject("SELECT stock_quantity FROM products WHERE product_id=?",Long.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE product_id=? AND seller_archived_at IS NOT NULL",Integer.class,id));assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM order_items WHERE product_id=?",Integer.class,id));
    }
    @Test @Order(24) @EnabledIfEnvironmentVariable(named="SELLER_BROWSER_TEST",matches="true")
    void archiveBrowserUsesRealApiAndPreservesArchivedRows()throws Exception {
        var command=new ProcessBuilder("node","tests/seller-archive.browser.mjs").directory(Path.of("..").toFile()).redirectErrorStream(true).redirectOutput(Path.of("target/seller-archive-browser.log").toFile());
        command.environment().put("SELLER_TEST_PORT",Integer.toString(port));command.environment().put("SELLER_TEST_TOKEN",a);command.environment().put("SELLER_TEST_SCHEMA",DB);
        var process=command.start();try{assertTrue(process.waitFor(180,TimeUnit.SECONDS));assertEquals(0,process.exitValue());}finally{if(process.isAlive())process.destroyForcibly();}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE name='Sản phẩm kiểm thử lưu trữ' AND seller_archived_at IS NOT NULL AND seller_listing_status='INACTIVE' AND status='INACTIVE'",Integer.class));
    }

    @Test @Order(25) @EnabledIfEnvironmentVariable(named="SELLER_BROWSER_TEST",matches="true")
    void freshCustomerToActiveSellerFullChromeFlow()throws Exception {
        for(var entry:Map.of("full-flow@example.invalid","0901000025","full-buyer@example.invalid","0901000026").entrySet()) {
            var registration=call("POST","/api/auth/register",null,Map.of("fullName","Full Flow Test","email",entry.getKey(),"phone",entry.getValue(),"password",PASSWORD));
            assertEquals(201,registration.status,registration.raw);
        }
        var command=new ProcessBuilder("node","tests/seller-full-flow.browser.mjs").directory(Path.of("..").toFile()).redirectErrorStream(true).redirectOutput(Path.of("target/seller-full-flow-browser.log").toFile());
        command.environment().put("SELLER_TEST_PORT",Integer.toString(port));command.environment().put("SELLER_TEST_TOKEN",login("full-flow@example.invalid"));command.environment().put("SELLER_TEST_ADMIN",admin);command.environment().put("SELLER_TEST_BUYER",login("full-buyer@example.invalid"));command.environment().put("SELLER_TEST_SCHEMA",DB);
        var process=command.start();try{assertTrue(process.waitFor(240,TimeUnit.SECONDS),"Full Chrome deadline");assertEquals(0,process.exitValue(),"Read target/seller-full-flow-browser.log");}finally{if(process.isAlive())process.destroyForcibly();}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM shops s JOIN users u ON u.user_id=s.owner_user_id WHERE u.email='full-flow@example.invalid' AND s.status='ACTIVE' AND s.representative_email='contact@example.invalid' AND s.ward='Phường thử nghiệm'",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE name='Trà kiểm thử toàn luồng' AND seller_archived_at IS NOT NULL AND shop_id IS NOT NULL",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM orders o JOIN users u ON u.user_id=o.user_id JOIN payments p ON p.order_id=o.order_id WHERE u.email='full-buyer@example.invalid' AND o.order_status='DELIVERED' AND p.payment_method='COD' AND p.payment_status='PENDING' AND o.shop_id IS NOT NULL",Integer.class));
    }

    @Test @Order(26) void codeLinkingUsesVerifiedSubAndDoesNotCreateOrMergeAccounts()throws Exception {
        var state=new java.util.concurrent.atomic.AtomicReference<String>();
        when(googleCodes.authorization(anyString(),anyString(),anyString())).thenAnswer(i->{state.set(i.getArgument(0));return "https://accounts.google.com/o/oauth2/v2/auth?state="+i.getArgument(0);});
        when(googleCodes.exchange(eq("synthetic-code"),anyString())).thenReturn("synthetic-id-token");
        when(google.verify(eq("synthetic-id-token"),anyString())).thenReturn(new GoogleTokenVerifier.Identity("code-link-synthetic-sub","different-contact@example.invalid","Synthetic"));
        var http=HttpClient.newHttpClient();
        for(int number=1;number<=2;number++) {
            String email="code-link-"+number+"@example.invalid";
            assertEquals(201,call("POST","/api/auth/register",null,Map.of("fullName","Code Link Synthetic","email",email,"phone","090100003"+number,"password",PASSWORD)).status);
            String token=login(email);if(number==1){var application=call("POST","/api/seller-applications",token,profile("Code Link Seller"));assertEquals(201,application.status);assertEquals(200,call("POST","/api/admin/seller-applications/"+application.data.get("applicationId").longValue()+"/approve",admin,null).status);}
            long before=jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class);
            var start=http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/auth/google/link/start")).header("Authorization","Bearer "+token).header("Origin","http://localhost:3000").POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,start.statusCode());String cookie=start.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
            var bad=http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/auth/google/link/callback?state=wrong&code=synthetic-code")).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(401,bad.statusCode());
            var callback=http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/auth/google/link/callback?state="+state.get()+"&code=synthetic-code")).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(303,callback.statusCode());assertEquals("http://localhost:3000/account?google_link=ready",callback.headers().firstValue("location").orElseThrow());
            assertNull(jdbc.queryForObject("SELECT google_sub FROM users WHERE email=?",String.class,email));
            var complete=http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/auth/google/link/complete")).header("Authorization","Bearer "+token).header("Origin","http://localhost:3000").header("Cookie",cookie).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());assertEquals(number==1?200:409,complete.statusCode());assertEquals(before,jdbc.queryForObject("SELECT COUNT(*) FROM users",Long.class));
            assertEquals("CUSTOMER",jdbc.queryForObject("SELECT role FROM users WHERE email=?",String.class,email));
            if(number==1){assertTrue(call("GET","/api/users/me",token,null).data.get("authorities").toString().contains("SELLER"));assertEquals(200,call("GET","/api/cart",token,null).status);assertEquals("code-link-synthetic-sub",jdbc.queryForObject("SELECT google_sub FROM users WHERE email=?",String.class,email));assertTrue(call("GET","/api/users/me/google-link",token,null).data.get("linked").booleanValue());}else assertNull(jdbc.queryForObject("SELECT google_sub FROM users WHERE email=?",String.class,email));
            assertEquals(401,http.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/auth/google/link/complete")).header("Authorization","Bearer "+token).header("Origin","http://localhost:3000").header("Cookie",cookie).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }
    @Test @Order(27) @EnabledIfEnvironmentVariable(named="SELLER_CHECKOUT_BROWSER_TEST",matches="true")
    void customerPurchasesSellerProductThroughChromeCartAndCheckout()throws Exception {
        assertEquals(201,call("POST","/api/auth/register",null,Map.of("fullName","Chrome Buyer","email","chrome-buyer@example.invalid","phone","0901000040","password",PASSWORD)).status);
        jdbc.update("UPDATE categories SET name='Trà xanh Thái Nguyên' WHERE category_id=1");
        long product=call("POST","/api/seller/products",a,Map.of("name","Trà Nõn Tôm Chrome Shop A","description","Chè xanh dùng trong nghiệm thu trên môi trường độc lập","categoryId",1,"regionId",1,"price",125000,"weightGrams",100,"imageUrl","/placeholder-teasmart.svg")).data.get("productId").longValue();
        assertEquals(200,adjustStock(product,UUID.randomUUID().toString(),8).statusCode());assertEquals(200,call("PATCH","/api/seller/products/"+product+"/status",a,Map.of("status","ACTIVE")).status);
        assertEquals(201,call("POST","/api/auth/register",null,Map.of("fullName","Seller B Chrome","email","chrome-seller-b@example.invalid","phone","0901000041","password",PASSWORD)).status);
        String chromeSellerB=login("chrome-seller-b@example.invalid");var application=call("POST","/api/seller-applications",chromeSellerB,profile("Gian hàng chè B"));assertEquals(201,application.status);var approved=call("POST","/api/admin/seller-applications/"+application.data.get("applicationId").longValue()+"/approve",admin,null);assertEquals(200,approved.status);long chromeShopB=approved.data.get("shopId").longValue();assertEquals(200,call("PATCH","/api/admin/shops/"+chromeShopB+"/catalog-store",admin,Map.of("storeId",1)).status);
        for(var entry:Map.of("Trà Móc Câu Chrome Shop A",a,"Trà Đinh Chrome Shop B",chromeSellerB).entrySet()){
            var created=call("POST","/api/seller/products",entry.getValue(),Map.of("name",entry.getKey(),"description","Dữ liệu nghiệm thu độc lập","categoryId",1,"regionId",1,"price",80000,"weightGrams",100,"imageUrl","/placeholder-teasmart.svg"));assertEquals(201,created.status,created.raw);long pid=created.data.get("productId").longValue();
            assertEquals(200,call("POST","/api/seller/products/"+pid+"/stock-adjustments",entry.getValue(),Map.of("delta",5,"reason","Nhập kho nghiệm thu Chrome")).status);assertEquals(200,call("PATCH","/api/seller/products/"+pid+"/status",entry.getValue(),Map.of("status","ACTIVE")).status);
        }
        jdbc.update("UPDATE products SET name='Trà xanh TeaSmart truyền thống',status='ACTIVE',stock_quantity=100 WHERE product_id=1 AND shop_id IS NULL");
        var command=new ProcessBuilder("node","tests/seller-customer-checkout.browser.mjs").directory(Path.of("..").toFile()).redirectErrorStream(true).redirectOutput(Path.of("target/seller-customer-checkout-browser.log").toFile());
        command.environment().put("SELLER_TEST_PORT",Integer.toString(port));command.environment().put("SELLER_TEST_TOKEN",login("chrome-buyer@example.invalid"));command.environment().put("SELLER_TEST_SELLER",a);command.environment().put("SELLER_TEST_SCHEMA",DB);command.environment().put("SELLER_TEST_PRODUCT",Long.toString(product));command.environment().put("SELLER_TEST_SHOP_NAME",jdbc.queryForObject("SELECT name FROM shops WHERE shop_id=?",String.class,shopA));
        var process=command.start();try{assertTrue(process.waitFor(240,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),"See target/seller-customer-checkout-browser.log");}finally{if(process.isAlive())process.destroyForcibly();}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM orders o JOIN users u ON u.user_id=o.user_id JOIN payments p ON p.order_id=o.order_id WHERE u.email='chrome-buyer@example.invalid' AND o.shop_id=? AND o.total_amount=250000 AND p.payment_method='COD' AND p.payment_status='PENDING'",Integer.class,shopA));
    }

    @Test @Order(28) @EnabledIfEnvironmentVariable(named="SELLER_STORE_BROWSER_TEST",matches="true")
    void adminLinksCatalogStoreThroughDrawerAndSellerCanCreateProduct()throws Exception {
        assertEquals(201,call("POST","/api/auth/register",null,Map.of("fullName","Chrome Store Owner","email","chrome-store-owner@example.invalid","phone","0901000050","password",PASSWORD)).status);
        String owner=login("chrome-store-owner@example.invalid");var application=call("POST","/api/seller-applications",owner,profile("Gian hàng liên kết Store Chrome"));assertEquals(201,application.status);var approval=call("POST","/api/admin/seller-applications/"+application.data.get("applicationId").longValue()+"/approve",admin,null);assertEquals(200,approval.status);long shop=approval.data.get("shopId").longValue();
        var inactive=call("POST","/api/admin/stores",admin,Map.of("name","HTX ngừng hoạt động fixture","status","INACTIVE"));assertEquals(201,inactive.status);long inactiveId=inactive.data.get("storeId").longValue();
        assertEquals(403,call("PATCH","/api/admin/shops/"+shop+"/catalog-store",owner,Map.of("storeId",1)).status);assertEquals(403,call("PATCH","/api/admin/shops/"+shop+"/catalog-store",login("plain@example.invalid"),Map.of("storeId",1)).status);
        assertEquals(404,call("PATCH","/api/admin/shops/"+shop+"/catalog-store",admin,Map.of("storeId",inactiveId)).status);assertEquals(404,call("PATCH","/api/admin/shops/999999999/catalog-store",admin,Map.of("storeId",1)).status);
        assertTrue(call("GET","/api/users/me/google-link",owner,null).data.get("canLink").booleanValue());
        when(google.verify(eq("google-only-browser-fixture"),anyString())).thenReturn(new GoogleTokenVerifier.Identity("google-only-browser-sub","google-only-browser@example.invalid","Google Only Fixture"));
        var googleHttp=HttpClient.newHttpClient();var challenge=googleHttp.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/auth/google/challenge")).header("Origin","http://localhost:3000").POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,challenge.statusCode());String googleCookie=challenge.headers().firstValue("set-cookie").orElseThrow().split(";")[0];
        var googleLogin=googleHttp.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/auth/google")).header("Origin","http://localhost:3000").header("Cookie",googleCookie).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("idToken","google-only-browser-fixture")))).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,googleLogin.statusCode());String googleOnlyToken=JSON.readTree(googleLogin.body()).get("accessToken").stringValue();assertNull(jdbc.queryForObject("SELECT password_hash FROM users WHERE google_sub='google-only-browser-sub'",String.class));
        var command=new ProcessBuilder("node","tests/admin-store-link.browser.mjs").directory(Path.of("..").toFile()).redirectErrorStream(true).redirectOutput(Path.of("target/admin-store-link-browser.log").toFile());command.environment().put("SELLER_TEST_PORT",Integer.toString(port));command.environment().put("SELLER_TEST_TOKEN",admin);command.environment().put("SELLER_TEST_OWNER",owner);command.environment().put("SELLER_TEST_LINKED",login("code-link-1@example.invalid"));command.environment().put("SELLER_TEST_GOOGLE_ONLY",googleOnlyToken);command.environment().put("SELLER_TEST_SHOP",Long.toString(shop));command.environment().put("SELLER_TEST_SCHEMA",DB);
        var process=command.start();try{assertTrue(process.waitFor(180,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),"See target/admin-store-link-browser.log");}finally{if(process.isAlive())process.destroyForcibly();}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE shop_id=? AND store_id=1 AND name='Trà sau xác minh Store qua Chrome'",Integer.class,shop));var before=call("GET","/api/admin/shops/"+shop+"/catalog-store",admin,null);var repeat=call("PATCH","/api/admin/shops/"+shop+"/catalog-store",admin,Map.of("storeId",1));assertEquals(200,repeat.status);assertEquals(before.raw,repeat.raw);
        var alternative=call("POST","/api/admin/stores",admin,Map.of("name","HTX tham chiếu khác fixture","status","ACTIVE"));assertEquals(201,alternative.status);assertEquals(409,call("PATCH","/api/admin/shops/"+shop+"/catalog-store",admin,Map.of("storeId",alternative.data.get("storeId").longValue())).status);assertEquals(1,call("GET","/api/admin/shops/"+shop+"/catalog-store",admin,null).data.get("storeId").longValue());
    }

    @AfterAll static void cleanup()throws Exception{
        try(var c=connection();var s=c.createStatement()){
            s.executeUpdate("DELETE FROM seller_stock_adjustments");
            s.executeUpdate("DELETE FROM payment_attempts");
            s.executeUpdate("DELETE FROM checkout_session_items");
            s.executeUpdate("DELETE FROM payments");
            s.executeUpdate("DELETE FROM order_items");
            s.executeUpdate("DELETE FROM orders");
            s.executeUpdate("DELETE FROM checkout_sessions");
            s.executeUpdate("DELETE FROM cart_items");
            s.executeUpdate("DELETE FROM products");
            var reverse=new ArrayList<>(TABLES);Collections.reverse(reverse);
            for(String table:reverse)s.executeUpdate("DELETE FROM `"+table+"`");
        }
        System.out.println("Seller synthetic fixture cleanup PASS; test schema retained: "+DB);
    }
}
