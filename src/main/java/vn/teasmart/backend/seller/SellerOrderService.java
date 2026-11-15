package vn.teasmart.backend.seller;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.teasmart.backend.dto.request.AdminOrderStatusRequest;
import vn.teasmart.backend.exception.ProfileException;
import vn.teasmart.backend.exception.ResourceNotFoundException;
import vn.teasmart.backend.service.AdminOrderService;

@Service
@ConditionalOnProperty(name="teasmart.seller.foundation-enabled",havingValue="true")
@Transactional(readOnly=true)
public class SellerOrderService {
    private final JdbcTemplate jdbc;
    private final SellerService sellers;
    private final AdminOrderService transitions;
    public SellerOrderService(JdbcTemplate jdbc,SellerService sellers,AdminOrderService transitions){this.jdbc=jdbc;this.sellers=sellers;this.transitions=transitions;}

    public record Summary(long orderId,String orderCode,String recipientName,LocalDateTime createdAt,
        BigDecimal subtotal,BigDecimal shippingFee,BigDecimal totalAmount,String orderStatus,
        String paymentMethod,String paymentStatus,long itemCount) {}
    public record Item(long productId,String productName,String imageUrl,BigDecimal unitPrice,long quantity,BigDecimal subtotal) {}
    public record Detail(long orderId,String orderCode,LocalDateTime createdAt,String orderStatus,
        String recipientName,String recipientPhone,String shippingAddress,String note,BigDecimal subtotal,
        BigDecimal shippingFee,BigDecimal totalAmount,String paymentMethod,String paymentStatus,
        List<Item> items) {}
    public record Page(List<Summary> content,int page,int size,long totalElements,long totalPages) {}
    private Long shop(long user){return sellers.myShop(user).shopId();}
    public Page list(long user,String q,String status,int page,int size){
        Long shop=shop(user);
        if(q.length()>100||page<0||size<1||size>50||(!status.isEmpty()&&!List.of("PENDING","CONFIRMED","SHIPPING","DELIVERED","CANCELLED").contains(status)))throw new IllegalArgumentException("Invalid order filter.");
        String where=" FROM orders o LEFT JOIN payments p ON p.order_id=o.order_id WHERE o.shop_id=? AND o.order_code LIKE ? AND (?='' OR o.order_status=?)";
        String pattern="%"+q.trim()+"%";
        long total=jdbc.queryForObject("SELECT COUNT(*)"+where,Long.class,shop,pattern,status,status);
        var rows=jdbc.query("SELECT o.order_id,o.order_code,o.recipient_name,o.created_at,o.subtotal,o.shipping_fee,o.total_amount,o.order_status,p.payment_method,p.payment_status,(SELECT COUNT(*) FROM order_items i WHERE i.order_id=o.order_id) item_count"+where+" ORDER BY o.order_id DESC LIMIT ? OFFSET ?",
            (r,n)->new Summary(r.getLong("order_id"),r.getString("order_code"),r.getString("recipient_name"),r.getTimestamp("created_at").toLocalDateTime(),r.getBigDecimal("subtotal"),r.getBigDecimal("shipping_fee"),r.getBigDecimal("total_amount"),r.getString("order_status"),r.getString("payment_method"),r.getString("payment_status"),r.getLong("item_count")),shop,pattern,status,status,size,(long)page*size);
        return new Page(rows,page,size,total,(total+size-1)/size);
    }
    public Detail get(long user,long id){
        Long shop=shop(user);
        var orders=jdbc.query("SELECT o.order_id,o.order_code,o.created_at,o.order_status,o.recipient_name,o.recipient_phone,o.shipping_address,o.note,o.subtotal,o.shipping_fee,o.total_amount,p.payment_method,p.payment_status FROM orders o LEFT JOIN payments p ON p.order_id=o.order_id WHERE o.order_id=? AND o.shop_id=?",
            (r,n)->new Detail(r.getLong("order_id"),r.getString("order_code"),r.getTimestamp("created_at").toLocalDateTime(),r.getString("order_status"),r.getString("recipient_name"),r.getString("recipient_phone"),r.getString("shipping_address"),r.getString("note"),r.getBigDecimal("subtotal"),r.getBigDecimal("shipping_fee"),r.getBigDecimal("total_amount"),r.getString("payment_method"),r.getString("payment_status"),List.of()),id,shop);
        if(orders.isEmpty())throw new ResourceNotFoundException("Order not found.");
        Detail order=orders.get(0);
        List<Item> items=jdbc.query("SELECT i.product_id,i.product_name,p.image_url,i.unit_price,i.quantity,i.subtotal FROM order_items i JOIN products p ON p.product_id=i.product_id WHERE i.order_id=? ORDER BY i.order_item_id",
            (r,n)->new Item(r.getLong("product_id"),r.getString("product_name"),r.getString("image_url"),r.getBigDecimal("unit_price"),r.getLong("quantity"),r.getBigDecimal("subtotal")),id);
        return new Detail(order.orderId(),order.orderCode(),order.createdAt(),order.orderStatus(),order.recipientName(),order.recipientPhone(),order.shippingAddress(),order.note(),order.subtotal(),order.shippingFee(),order.totalAmount(),order.paymentMethod(),order.paymentStatus(),items);
    }
    @Transactional
    public Detail update(long user,long id,String next){
        Long shop=shop(user);
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM orders WHERE order_id=? AND shop_id=?)",Boolean.class,id,shop)))throw new ResourceNotFoundException("Order not found.");
        if("CANCELLED".equals(next))throw new ProfileException(409,"SELLER_CANCELLATION_UNAVAILABLE","Seller chưa được phép hủy đơn.");
        transitions.updateStatus(id,new AdminOrderStatusRequest(next));
        return get(user,id);
    }
}
