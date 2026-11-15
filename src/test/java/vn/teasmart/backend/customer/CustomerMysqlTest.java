package vn.teasmart.backend.customer;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.nio.file.Files;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.json.JsonMapper;
import vn.teasmart.backend.repository.UserRepository;
import vn.teasmart.backend.entity.User;
import vn.teasmart.backend.security.JwtService;
import vn.teasmart.backend.service.PaymentReconciliationService;
import vn.teasmart.backend.config.ReviewImageCleanupConfig;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
@EnabledIfEnvironmentVariable(named="CUSTOMER_TEST_DB",matches="teasmart_product_test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK)
class CustomerMysqlTest {
    static final String URL="jdbc:mysql://127.0.0.1:3306/teasmart_product_test";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) throws Exception {
        r.add("teasmart.seller.enabled",()->false);
        r.add("spring.datasource.url",()->URL);
        r.add("spring.datasource.username",()->System.getenv("DB_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv("DB_PASSWORD"));
        r.add("spring.jpa.hibernate.ddl-auto",()->"validate");r.add("spring.sql.init.mode",()->"never");
        r.add("teasmart.vnpay.enabled",()->false);r.add("teasmart.mail.enabled",()->false);r.add("teasmart.google.enabled",()->false);
        r.add("teasmart.review-images.storage-root",()->System.getProperty("java.io.tmpdir")+"/teasmart-customer-mysql-images");
        r.add("teasmart.jwt.secret-base64",()->Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
        r.add("teasmart.jwt.issuer",()->"customer-mysql-test");
    }
    @MockitoBean PaymentReconciliationService reconciliation;
    @MockitoBean ReviewImageCleanupConfig cleanup;
    @Autowired WebApplicationContext context; @Autowired FilterChainProxy security;
    @Autowired JdbcTemplate jdbc; @Autowired PlatformTransactionManager manager;
    @Autowired UserRepository users; @Autowired JwtService jwt; @Autowired PasswordEncoder encoder;
    MockMvc mvc; final JsonMapper json=JsonMapper.builder().build(); int requests;
    String call(String method,String path,String token,String body,int expected) throws Exception {
        var req=switch(method){case "PUT"->put(path);case "DELETE"->delete(path);case "POST"->post(path);case "PATCH"->patch(path);default->get(path);};
        if(token!=null)req.header("Authorization","Bearer "+token);
        if(body!=null)req.contentType("application/json").content(body);
        if(method.equals("POST")&&path.equals("/api/orders"))req.header("Idempotency-Key","rollback-only-checkout");
        var response=mvc.perform(req).andReturn().getResponse(); requests++;
        assertEquals(expected,response.getStatus(),method+" "+path+" status mismatch");return response.getContentAsString();
    }
    User user(String role,String password) {
        User u=new User();u.setFullName("Rollback fixture");u.setEmail(UUID.randomUUID()+"@example.invalid");u.setRole(role);u.setStatus("ACTIVE");u.setPasswordHash(encoder.encode(password));u.setCreatedAt(java.time.LocalDateTime.now());u.setUpdatedAt(u.getCreatedAt());return users.saveAndFlush(u);
    }
    Map<String,String> snapshot() {
        var result=new TreeMap<String,String>();
        var tables=jdbc.queryForList("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_TYPE='BASE TABLE'",String.class);
        for(String table:tables){if(!table.matches("[a-z_]+"))throw new IllegalStateException("Unexpected table");
            var rows=jdbc.query("SELECT * FROM `"+table+"`",(rs,n)->{var vals=new ArrayList<String>();for(int i=1;i<=rs.getMetaData().getColumnCount();i++){Object v=rs.getObject(i);vals.add(v==null?null:v instanceof byte[] b?Base64.getEncoder().encodeToString(b):v.toString());}return json.writeValueAsString(vals);});
            Collections.sort(rows);try{result.put(table,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(rows).getBytes(StandardCharsets.UTF_8))));}catch(Exception e){throw new IllegalStateException("Digest failed");}
        }return result;
    }
    @Test void realMysqlWishlistCartOrdersSecurityAndRollback() throws Exception {
        try(var c=jdbc.getDataSource().getConnection()) { assertEquals("teasmart_product_test",c.getCatalog());assertTrue(c.getMetaData().getURL().startsWith(URL)); }
        assertEquals("teasmart_product_test",jdbc.queryForObject("SELECT DATABASE()",String.class));
        var before=snapshot(); assertEquals(19,before.size());
        mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        var tx=new TransactionTemplate(manager);
        try {
            tx.executeWithoutResult(status->{try {
                String password=UUID.randomUUID().toString();User a=user("CUSTOMER",password),b=user("CUSTOMER",password),admin=user("ADMIN",password);
                String ta=jwt.generateAccessToken(a),tb=jwt.generateAccessToken(b),td=jwt.generateAccessToken(admin);
                Long existing=jdbc.queryForObject("SELECT p.product_id FROM products p JOIN categories c USING(category_id) JOIN tea_regions r USING(region_id) JOIN stores s USING(store_id) WHERE p.status='ACTIVE' AND c.status='ACTIVE' AND r.status='ACTIVE' AND s.status='ACTIVE' LIMIT 1",Long.class);
                String slug="rollback-"+UUID.randomUUID();
                jdbc.update("INSERT INTO products(category_id,region_id,store_id,name,slug,price,stock_quantity,weight_grams,status,created_at,updated_at) SELECT category_id,region_id,store_id,'Rollback fixture',?,100000,10,100,'ACTIVE',NOW(),NOW() FROM products WHERE product_id=?",slug,existing);
                Long product=jdbc.queryForObject("SELECT product_id FROM products WHERE slug=?",Long.class,slug);
                call("GET","/api/wishlist",null,null,401);call("GET","/api/wishlist",td,null,403);
                assertEquals(0,json.readTree(call("GET","/api/wishlist",ta,null,200)).size());
                String path="/api/wishlist/products/"+product;
                call("PUT",path,ta,null,200);call("PUT",path,ta,null,200);
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM wishlists WHERE user_id=?",Integer.class,a.getUserId()));
                assertEquals(0,json.readTree(call("GET","/api/wishlist",tb,null,200)).size());
                call("DELETE",path,tb,null,200);assertEquals(1,json.readTree(call("GET","/api/wishlist",ta,null,200)).size());
                call("DELETE",path,ta,null,200);call("DELETE",path,ta,null,200);
                assertEquals(0,json.readTree(call("GET","/api/wishlist",ta,null,200)).size());
                call("PUT","/api/wishlist/products/0",ta,null,400);call("PUT","/api/wishlist/products/9223372036854775807",ta,null,404);
                String login=json.writeValueAsString(Map.of("email",a.getEmail(),"password",password));call("POST","/api/auth/login",null,login,200);
                call("POST","/api/auth/login",null,json.writeValueAsString(Map.of("email",a.getEmail(),"password","wrong")),401);
                call("GET","/api/admin/orders",ta,null,403);call("GET","/api/users/me",ta,null,200);
                call("POST","/api/cart/items",ta,"{\"productId\":"+product+",\"quantity\":2}",200);
                assertEquals(0,json.readTree(call("GET","/api/cart",tb,null,200)).get("items").size());
                String shipping="{\"recipientName\":\"Rollback fixture\",\"recipientPhone\":\"0912345678\",\"shippingAddress\":\"Rollback address\",\"note\":null}";
                var order=json.readTree(call("POST","/api/orders",ta,shipping,201));long id=order.get("orderId").longValue();
                call("POST","/api/orders",ta,shipping,200);assertEquals(8L,jdbc.queryForObject("SELECT stock_quantity FROM products WHERE product_id=?",Long.class,product));
                call("GET","/api/orders/"+id,ta,null,200);call("GET","/api/orders/"+id,tb,null,404);
                assertEquals(1,json.readTree(call("GET","/api/orders",ta,null,200)).get("content").size());
                assertEquals(0,json.readTree(call("GET","/api/orders",tb,null,200)).get("content").size());
                call("PATCH","/api/orders/"+id+"/cancel",ta,null,200);assertEquals(10L,jdbc.queryForObject("SELECT stock_quantity FROM products WHERE product_id=?",Long.class,product));
                a.setStatus("INACTIVE");users.saveAndFlush(a);call("GET","/api/wishlist",ta,null,401);
            } catch(Exception e){throw new IllegalStateException("Customer integration failed",e);} finally {status.setRollbackOnly();}});
        } finally {assertEquals(before,snapshot(),"Existing test data changed after rollback");}
        System.out.println("CUSTOMER_MYSQL_REQUESTS="+requests+"; DATASOURCE=teasmart_product_test; DATA_DIGEST_19_TABLES_MATCH=true; FIXTURES_ROLLED_BACK=true");
    }
    @Test void adminCatalogCrudUsesOnlyRollbackFixtures() throws Exception {
        assertEquals("teasmart_product_test",jdbc.queryForObject("SELECT DATABASE()",String.class));
        var before=snapshot();mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        try {new TransactionTemplate(manager).executeWithoutResult(tx->{try{
            String token=jwt.generateAccessToken(user("ADMIN",UUID.randomUUID().toString()));
            String suffix=UUID.randomUUID().toString();var category=new HashMap<String,Object>();category.put("name","Rollback category "+suffix);category.put("description","Fixture only");category.put("status","ACTIVE");
            long categoryId=json.readTree(call("POST","/api/admin/categories",token,json.writeValueAsString(category),201)).get("categoryId").longValue();
            category.put("description","Updated fixture");call("PUT","/api/admin/categories/"+categoryId,token,json.writeValueAsString(category),200);
            call("PATCH","/api/admin/categories/"+categoryId+"/status",token,"{\"status\":\"INACTIVE\"}",200);call("PATCH","/api/admin/categories/"+categoryId+"/status",token,"{\"status\":\"ACTIVE\"}",200);
            call("GET","/api/admin/categories/"+categoryId,token,null,200);
            String region=json.writeValueAsString(Map.of("name","Rollback region "+suffix,"status","ACTIVE"));
            long regionId=json.readTree(call("POST","/api/admin/tea-regions",token,region,201)).get("regionId").longValue();
            call("PUT","/api/admin/tea-regions/"+regionId,token,region,200);call("PATCH","/api/admin/tea-regions/"+regionId+"/status",token,"{\"status\":\"INACTIVE\"}",200);call("PATCH","/api/admin/tea-regions/"+regionId+"/status",token,"{\"status\":\"ACTIVE\"}",200);
            long storeId=json.readTree(call("POST","/api/admin/stores",token,json.writeValueAsString(Map.of("name","Rollback store "+suffix,"status","ACTIVE")),201)).get("storeId").longValue();
            var product=new HashMap<String,Object>();product.put("categoryId",categoryId);product.put("regionId",regionId);product.put("storeId",storeId);product.put("name","Rollback product "+suffix);product.put("price",123000);product.put("weightGrams",100);product.put("stockQuantity",30);product.put("status","ACTIVE");product.put("imageUrl","/demo/tea/ts-demo-001.png");
            var created=json.readTree(call("POST","/api/admin/products",token,json.writeValueAsString(product),201));long productId=created.get("productId").longValue();String slug=created.get("slug").stringValue();
            call("GET","/api/products/"+slug,null,null,200);product.put("price",125000);call("PUT","/api/admin/products/"+productId,token,json.writeValueAsString(product),200);
            call("PATCH","/api/admin/products/"+productId+"/status",token,"{\"status\":\"INACTIVE\"}",200);call("GET","/api/products/"+slug,null,null,404);
            call("PATCH","/api/admin/products/"+productId+"/status",token,"{\"status\":\"ACTIVE\"}",200);call("GET","/api/products/"+slug,null,null,200);
            call("GET","/api/admin/products/"+productId,token,null,200);
        }catch(Exception e){throw new IllegalStateException("Rollback Admin CRUD failed",e);}finally{tx.setRollbackOnly();}});
        }finally{assertEquals(before,snapshot(),"Existing and seeded data must remain unchanged");}
        System.out.println("ADMIN_CATALOG_MYSQL_REQUESTS="+requests+"; DATA_DIGEST_19_TABLES_MATCH=true; FIXTURES_ROLLED_BACK=true");
    }

    @Test void twoLevelAddressesPersistInTestDatabaseAndDoNotRewriteOrderShippingSnapshots() throws Exception {
        assertEquals("teasmart_product_test",jdbc.queryForObject("SELECT DATABASE()",String.class));
        var before=snapshot();mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
        try {
            new TransactionTemplate(manager).executeWithoutResult(tx->{try {
                String password=UUID.randomUUID().toString();
                User customer=user("CUSTOMER",password),other=user("CUSTOMER",password);
                String customerToken=jwt.generateAccessToken(customer),otherToken=jwt.generateAccessToken(other);
                String ordersBefore=snapshot().get("orders");
                String firstBody=json.writeValueAsString(Map.of("recipientName","Two-level fixture","phone","0912345678",
                        "province","Tỉnh Thái Nguyên","ward","Xã Tân Cương","detailAddress","Số 1 đường Trà"));
                var first=json.readTree(call("POST","/api/addresses",customerToken,firstBody,201));
                long firstId=first.get("addressId").longValue();
                assertEquals("",first.get("district").asText());
                assertTrue(first.get("isDefault").booleanValue());
                var customerRows=json.readTree(call("GET","/api/addresses",customerToken,null,200));
                assertEquals(1,customerRows.size());
                assertEquals("Xã Tân Cương",customerRows.get(0).get("ward").asText());
                assertEquals(0,json.readTree(call("GET","/api/addresses",otherToken,null,200)).size());

                String legacyBody=json.writeValueAsString(Map.of("recipientName","Legacy fixture","phone","0912345678",
                        "province","Thái Nguyên","district","Thành phố Thái Nguyên","ward","Phường Trưng Vương",
                        "detailAddress","Số 2 đường Trà"));
                var legacy=json.readTree(call("POST","/api/addresses",customerToken,legacyBody,201));
                long legacyId=legacy.get("addressId").longValue();
                assertEquals("Thành phố Thái Nguyên",legacy.get("district").asText());
                assertFalse(legacy.get("isDefault").booleanValue());
                call("PATCH","/api/addresses/"+legacyId+"/default",customerToken,null,200);
                var updated=json.readTree(call("PUT","/api/addresses/"+legacyId,customerToken,legacyBody,200));
                assertEquals("Thành phố Thái Nguyên",updated.get("district").asText());
                assertTrue(updated.get("isDefault").booleanValue());
                call("DELETE","/api/addresses/"+legacyId,customerToken,null,204);
                assertTrue(json.readTree(call("GET","/api/addresses",customerToken,null,200)).get(0).get("isDefault").booleanValue());
                assertEquals(ordersBefore,snapshot().get("orders"),"Saving an address must not rewrite existing order shipping snapshots");
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM addresses WHERE address_id=? AND district=''",Integer.class,firstId));
            } catch(Exception e){throw new IllegalStateException("Address integration failed",e);} finally {tx.setRollbackOnly();}});
        } finally {assertEquals(before,snapshot(),"All address fixtures must roll back in the isolated test database");}
    }

}
