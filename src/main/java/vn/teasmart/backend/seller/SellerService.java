package vn.teasmart.backend.seller;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.exception.ProfileException;
import vn.teasmart.backend.exception.ResourceNotFoundException;
import vn.teasmart.backend.service.PaymentTime;

@Service
@Transactional(readOnly=true)
public class SellerService {
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    @Value("${teasmart.seller.foundation-enabled:false}")
    private boolean productsEnabled;
    @Value("${teasmart.seller.profile-details-enabled:false}") private boolean detailsEnabled;
    public SellerService(JdbcTemplate jdbc,@Value("${teasmart.seller.enabled:false}") boolean enabled) {
        this.jdbc=jdbc; this.enabled=enabled;
    }
    public boolean enabled() { return enabled; }
    public boolean foundationEnabled() { return enabled && productsEnabled; }
    public boolean profileDetailsEnabled() { return enabled && detailsEnabled; }
    private void available() { if(!enabled) throw new ProfileException(503,"SELLER_DISABLED","Kênh người bán chưa được mở."); }
    public boolean activeSeller(Long userId) {
        return enabled && Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM shops s JOIN users u ON u.user_id=s.owner_user_id WHERE s.owner_user_id=? AND s.status='ACTIVE' AND u.status='ACTIVE' AND u.role='CUSTOMER')",Boolean.class,userId));
    }
    public record State(boolean enabled,String status,SellerApplication application,Shop shop,boolean productsEnabled,boolean profileDetailsEnabled) {}
    public State state(Long userId) {
        if(!enabled) return new State(false,"DISABLED",null,null,false,false);
        requireUser(userId,"CUSTOMER",false);
        Shop shop=owned(userId);
        SellerApplication app=jdbc.query("SELECT * FROM seller_applications WHERE applicant_user_id=? ORDER BY application_id DESC LIMIT 1",APP,userId).stream().findFirst().orElse(null);
        return new State(true,shop!=null?shop.status():app!=null?app.status():"NONE",app,shop,productsEnabled,detailsEnabled);
    }
    // All writes lock owner first, then application/shop. Admin status changes use the same order.
    private void requireUser(Long id,String role,boolean lock) {
        available();
        var users=jdbc.queryForList("SELECT role,status FROM users WHERE user_id=?"+(lock?" FOR UPDATE":""),id);
        if(users.isEmpty() || !"ACTIVE".equals(users.get(0).get("status")) || !role.equals(users.get(0).get("role")))
            throw new ProfileException(403,"SELLER_FORBIDDEN","Tài khoản không có quyền thực hiện thao tác.");
    }
    private Shop owned(Long owner) {
        return jdbc.query("SELECT * FROM shops WHERE owner_user_id=?",SHOP,owner).stream().findFirst().orElse(null);
    }
    private Shop byId(Long id) {
        return jdbc.query("SELECT * FROM shops WHERE shop_id=?",SHOP,id).stream().findFirst().orElseThrow(()->new ResourceNotFoundException("Shop not found."));
    }
    public Shop myShop(Long owner) {
        requireUser(owner,"CUSTOMER",false);
        Shop shop=owned(owner);
        if(shop==null || !"ACTIVE".equals(shop.status())) throw new ProfileException(403,"SELLER_INACTIVE","Gian hàng chưa hoạt động hoặc đang bị khóa.");
        return shop;
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public SellerApplication submit(Long owner,SellerRequests.Profile p) {
        requireUser(owner,"CUSTOMER",true);
        if(!detailsEnabled && p.businessType()!=null) throw new ProfileException(503,"SELLER_DETAILS_DISABLED","Thông tin đăng ký mở rộng chưa được mở trên môi trường này.");
        if(owned(owner)!=null) throw conflict("SHOP_ALREADY_EXISTS","Tài khoản đã có gian hàng.");
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM seller_applications WHERE applicant_user_id=? AND status='PENDING')",Boolean.class,owner)))
            throw conflict("APPLICATION_PENDING","Bạn đã có hồ sơ đang chờ duyệt.");
        jdbc.update("INSERT INTO seller_applications(applicant_user_id,shop_name,representative_name,phone,business_address,description,status,created_at) VALUES(?,?,?,?,?,?,'PENDING',?)",
            owner,p.shopName(),p.representativeName(),p.phone(),p.businessAddress(),p.description(),PaymentTime.now());
        if(detailsEnabled && p.businessType()!=null) saveDetails("seller_applications","application_id",state(owner).application().applicationId(),p);
        return state(owner).application();
    }
    public SellerApplication application(Long id) {
        available();
        return jdbc.query("SELECT * FROM seller_applications WHERE application_id=?",APP,id).stream().findFirst().orElseThrow(()->new ResourceNotFoundException("Application not found."));
    }
    public record Page<T>(List<T> content,int page,int size,long totalElements,long totalPages) {}
    public Page<SellerApplication> applications(String status,int page,int size) { return applications(status,page,size,null,null,null,null); }
    public Page<SellerApplication> applications(String status,int page,int size,String q,String type,java.time.LocalDate from,java.time.LocalDate to) {
        available();validatePage(page,size);if(!List.of("PENDING","APPROVED","REJECTED").contains(status))throw new IllegalArgumentException();
        var f=filter(status,q,type,from,to,"shop_name");long count=jdbc.queryForObject("SELECT COUNT(*) FROM seller_applications"+f.sql(),Long.class,f.args().toArray());
        var args=new java.util.ArrayList<Object>(f.args());args.add(size);args.add((long)page*size);
        return new Page<>(jdbc.query("SELECT * FROM seller_applications"+f.sql()+" ORDER BY application_id DESC LIMIT ? OFFSET ?",APP,args.toArray()),page,size,count,(count+size-1)/size);
    }
    public Page<Shop> shops(String status,int page,int size) { return shops(status,page,size,null,null,null,null); }
    public Page<Shop> shops(String status,int page,int size,String q,String type,java.time.LocalDate from,java.time.LocalDate to) {
        available();validatePage(page,size);if(!List.of("ACTIVE","SUSPENDED").contains(status))throw new IllegalArgumentException();
        var f=filter(status,q,type,from,to,"name");long count=jdbc.queryForObject("SELECT COUNT(*) FROM shops"+f.sql(),Long.class,f.args().toArray());
        var args=new java.util.ArrayList<Object>(f.args());args.add(size);args.add((long)page*size);
        return new Page<>(jdbc.query("SELECT * FROM shops"+f.sql()+" ORDER BY shop_id DESC LIMIT ? OFFSET ?",SHOP,args.toArray()),page,size,count,(count+size-1)/size);
    }
    private record Filter(String sql,java.util.List<Object> args){}
    private Filter filter(String status,String q,String type,java.time.LocalDate from,java.time.LocalDate to,String name) {
        var sql=new StringBuilder(" WHERE status=?");var args=new java.util.ArrayList<Object>();args.add(status);
        if(q!=null&&!q.isBlank()){if(q.length()>150)throw new IllegalArgumentException();sql.append(" AND ("+name+" LIKE ? OR representative_name LIKE ? OR phone LIKE ?"+(detailsEnabled?" OR representative_email LIKE ?":"")+")");for(int i=0;i<(detailsEnabled?4:3);i++)args.add("%"+q.trim()+"%");}
        if(type!=null&&!type.isBlank()){if(!detailsEnabled)throw new ProfileException(503,"SELLER_DETAILS_DISABLED","Bộ lọc loại hình chưa được mở.");sql.append(" AND business_type=?");args.add(type);}
        if(from!=null){sql.append(" AND created_at>=?");args.add(from.atStartOfDay());}
        if(to!=null){sql.append(" AND created_at<?");args.add(to.plusDays(1).atStartOfDay());}
        if(from!=null&&to!=null&&from.isAfter(to))throw new IllegalArgumentException();
        return new Filter(sql.toString(),args);
    }
    public Shop adminShop(Long id) { available(); return byId(id); }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public Shop approve(Long admin,Long id) {
        requireUser(admin,"ADMIN",false);
        SellerApplication original=application(id);
        requireUser(original.applicantUserId(),"CUSTOMER",true);
        SellerApplication app=lockedApplication(id);
        if("APPROVED".equals(app.status())) {
            Shop existing=owned(app.applicantUserId());
            if(existing!=null && id.equals(existing.approvedApplicationId())) return existing;
            throw conflict("APPROVAL_CONFLICT","Hồ sơ đã được xử lý.");
        }
        if(!"PENDING".equals(app.status()) || owned(app.applicantUserId())!=null) throw conflict("APPLICATION_DECIDED","Hồ sơ đã được xử lý.");
        LocalDateTime now=PaymentTime.now();
        jdbc.update("UPDATE seller_applications SET status='APPROVED',reviewer_user_id=?,reviewed_at=? WHERE application_id=?",admin,now,id);
        jdbc.update("INSERT INTO shops(owner_user_id,approved_application_id,name,representative_name,phone,business_address,description,status,status_changed_by,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'ACTIVE',?,?,?)",
            app.applicantUserId(),id,app.shopName(),app.representativeName(),app.phone(),app.businessAddress(),app.description(),admin,now,now);
        if(detailsEnabled) jdbc.update("UPDATE shops s JOIN seller_applications a ON a.application_id=s.approved_application_id SET s.business_type=a.business_type, s.representative_email=a.representative_email, s.province=a.province, s.ward=a.ward, s.address_detail=a.address_detail, s.application_logo_url=a.application_logo_url, s.cover_url=a.cover_url WHERE s.approved_application_id=?",id);
        return owned(app.applicantUserId());
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public SellerApplication reject(Long admin,Long id,String reason) {
        requireUser(admin,"ADMIN",false);
        var original=application(id);
        requireUser(original.applicantUserId(),"CUSTOMER",true);
        var app=lockedApplication(id);
        if("REJECTED".equals(app.status()) && reason.equals(app.rejectionReason())) return app;
        if(!"PENDING".equals(app.status())) throw conflict("APPLICATION_DECIDED","Hồ sơ đã được xử lý.");
        jdbc.update("UPDATE seller_applications SET status='REJECTED',rejection_reason=?,reviewer_user_id=?,reviewed_at=? WHERE application_id=?",reason,admin,PaymentTime.now(),id);
        return application(id);
    }
    private SellerApplication lockedApplication(Long id) {
        return jdbc.queryForObject("SELECT * FROM seller_applications WHERE application_id=? FOR UPDATE",APP,id);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public Shop update(Long owner,SellerRequests.Profile p) {
        requireUser(owner,"CUSTOMER",true);
        Shop shop=myShop(owner);
        jdbc.update("UPDATE shops SET name=?,representative_name=?,phone=?,business_address=?,description=?,updated_at=? WHERE shop_id=? AND owner_user_id=?",
            p.shopName(),p.representativeName(),p.phone(),p.businessAddress(),p.description(),PaymentTime.now(),shop.shopId(),owner);
        if(detailsEnabled && p.businessType()!=null) saveDetails("shops","shop_id",shop.shopId(),p);
        return myShop(owner);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public Shop status(Long admin,Long id,SellerRequests.Status request) {
        requireUser(admin,"ADMIN",false);
        Shop initial=byId(id);
        // Owner may be account-blocked; Admin can still manage the shop, without granting account access.
        jdbc.queryForList("SELECT user_id FROM users WHERE user_id=? FOR UPDATE",initial.ownerUserId());
        Shop shop=byId(id);
        if(request.status().equals(shop.status())) return shop;
        if("SUSPENDED".equals(request.status()) && (request.reason()==null || request.reason().isBlank()))
            throw new ProfileException(400,"REASON_REQUIRED","Vui lòng nhập lý do khóa gian hàng.");
        jdbc.update("UPDATE shops SET status=?,status_reason=?,status_changed_by=?,updated_at=? WHERE shop_id=?",
            request.status(),"SUSPENDED".equals(request.status())?request.reason():null,admin,PaymentTime.now(),id);
        return byId(id);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Shop lockMyShop(Long owner) { requireUser(owner,"CUSTOMER",true); return myShop(owner); }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public Shop setLogo(Long owner,String url) {
        requireUser(owner,"CUSTOMER",true); Shop shop=myShop(owner);
        jdbc.update("UPDATE shops SET logo_url=?,updated_at=? WHERE shop_id=?",url,PaymentTime.now(),shop.shopId());
        return myShop(owner);
    }
    public boolean referencesLogo(String url) {
        return enabled && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM shops WHERE logo_url=? AND status='ACTIVE')",Boolean.class,url));
    }
    private void saveDetails(String table,String key,Long id,SellerRequests.Profile p) {
        if(!java.util.List.of("INDIVIDUAL","COOPERATIVE","COMPANY").contains(p.businessType()==null?"":p.businessType()) || p.representativeEmail()==null || p.representativeEmail().isBlank() || p.province()==null || p.province().isBlank() || p.ward()==null || p.ward().isBlank() || p.addressDetail()==null || p.addressDetail().isBlank())
            throw new ProfileException(400,"SELLER_DETAILS_REQUIRED","Vui lòng điền loại hình kinh doanh, email và địa chỉ đầy đủ.");
        for(String url:java.util.Arrays.asList(p.applicationLogoUrl(),p.coverUrl())) if(url!=null && !url.isBlank() && !vn.teasmart.backend.service.ProductGalleryService.validUrl(url)) throw new IllegalArgumentException("Invalid image URL");
        jdbc.update("UPDATE "+table+" SET business_type=?, representative_email=?, province=?, ward=?, address_detail=?, application_logo_url=?, cover_url=? WHERE "+key+"=?",p.businessType(), p.representativeEmail(), p.province(), p.ward(), p.addressDetail(), p.applicationLogoUrl(), p.coverUrl(),id);
    }
    private static String optional(ResultSet r,String column)throws SQLException {
        var metadata=r.getMetaData();for(int i=1;i<=metadata.getColumnCount();i++)if(column.equalsIgnoreCase(metadata.getColumnLabel(i)))return r.getString(column);return null;
    }
    private static void validatePage(int page,int size) { if(page<0||size<1||size>50)throw new IllegalArgumentException(); }
    private static ProfileException conflict(String code,String message) { return new ProfileException(409,code,message); }
    private static LocalDateTime date(ResultSet r,String key)throws SQLException {var v=r.getTimestamp(key);return v==null?null:v.toLocalDateTime();}
    static final RowMapper<SellerApplication> APP=(r,n)->new SellerApplication(r.getLong("application_id"),r.getLong("applicant_user_id"),r.getString("shop_name"),
        r.getString("representative_name"),r.getString("phone"),r.getString("business_address"),r.getString("description"),r.getString("status"),
        r.getObject("reviewer_user_id",Long.class),r.getString("rejection_reason"),date(r,"created_at"),date(r,"reviewed_at"),optional(r,"business_type"),optional(r,"representative_email"),optional(r,"province"),optional(r,"ward"),optional(r,"address_detail"),optional(r,"application_logo_url"),optional(r,"cover_url"));
    static final RowMapper<Shop> SHOP=(r,n)->new Shop(r.getLong("shop_id"),r.getLong("owner_user_id"),r.getLong("approved_application_id"),
        r.getString("name"),r.getString("representative_name"),r.getString("phone"),r.getString("business_address"),r.getString("description"),
        r.getString("logo_url"),r.getString("status"),r.getString("status_reason"),r.getLong("status_changed_by"),date(r,"created_at"),date(r,"updated_at"),optional(r,"business_type"),optional(r,"representative_email"),optional(r,"province"),optional(r,"ward"),optional(r,"address_detail"),optional(r,"application_logo_url"),optional(r,"cover_url"));
}
