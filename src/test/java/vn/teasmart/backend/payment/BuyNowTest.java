package vn.teasmart.backend.payment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.math.BigDecimal;
import jakarta.persistence.EntityManager;
import vn.teasmart.backend.entity.*;
import vn.teasmart.backend.repository.*;
import vn.teasmart.backend.service.*;
import vn.teasmart.backend.dto.request.*;
import vn.teasmart.backend.enums.*;
import vn.teasmart.backend.config.VnpaySettings;
import vn.teasmart.backend.notification.OrderNotifications;
class BuyNowTest {
 UserRepository users=mock(UserRepository.class); CartRepository carts=mock(CartRepository.class);
 CartItemRepository cartItems=mock(CartItemRepository.class); ProductRepository products=mock(ProductRepository.class);
 OrderRepository orders=mock(OrderRepository.class); OrderItemRepository items=mock(OrderItemRepository.class);
 PaymentRepository payments=mock(PaymentRepository.class); EntityManager em=mock(EntityManager.class);
 VnpayGateway gateway=mock(VnpayGateway.class); VnpaySettings settings=mock(VnpaySettings.class);
 OrderService service=new OrderService(users,carts,cartItems,products,orders,items,payments,em,mock(OrderStockService.class),settings,gateway,mock(OrderNotifications.class));
 PlaceOrderRequest shipping=new PlaceOrderRequest("Fixture","0912345678","Test address","Gift note");
 BuyNowRequest request=new BuyNowRequest(17L,3L,shipping); Product product; Order saved; Payment payment;
 @BeforeEach void setup(){
  User u=new User();u.setUserId(1L);u.setStatus("ACTIVE");u.setRole("CUSTOMER");when(users.findLockedByUserId(1L)).thenReturn(Optional.of(u));
  product=new Product();product.setProductId(17L);product.setName("Fixture");product.setPrice(new BigDecimal("100.00"));product.setStockQuantity(10L);product.setStatus("ACTIVE");
  Category c=new Category();c.setStatus("ACTIVE");product.setCategory(c);TeaRegion r=new TeaRegion();r.setStatus("ACTIVE");product.setRegion(r);Store st=new Store();st.setStatus("ACTIVE");product.setStore(st);
  when(products.findLockedByProductId(17L)).thenReturn(Optional.of(product));
  when(orders.saveAndFlush(any())).thenAnswer(i->{saved=i.getArgument(0);saved.setOrderId(7L);return saved;});
  when(items.save(any())).thenAnswer(i->{OrderItem item=i.getArgument(0);item.setOrderItemId(9L);return item;});
  when(payments.save(any())).thenAnswer(i->{payment=i.getArgument(0);return payment;});when(settings.expiryMinutes()).thenReturn(15);
 }
 @Test void codUsesServerPriceAndNeverTouchesCart(){var response=service.buyNow(1L,request,"buy-now-key-123456",PaymentMethod.COD,"127.0.0.1");assertEquals(new BigDecimal("300.00"),response.order().totalAmount());assertEquals(7L,product.getStockQuantity());assertEquals(PaymentStatus.PENDING,payment.getPaymentStatus());assertNull(payment.getPaidAt());assertEquals("Gift note",saved.getNote());verifyNoInteractions(carts,cartItems);}
 @Test void legacyVnpayBuyNowRequiresSessionAndCreatesNoOrder(){var error=assertThrows(vn.teasmart.backend.exception.PaymentException.class,()->service.buyNow(1L,request,"buy-now-key-123456",PaymentMethod.ONLINE,"127.0.0.1"));assertEquals("CHECKOUT_SESSION_REQUIRED",error.getCode());verify(orders,never()).saveAndFlush(any());verify(payments,never()).save(any());verifyNoInteractions(gateway,carts,cartItems,products);}
 @Test void rejectsInsufficientStockBeforeSaving(){product.setStockQuantity(2L);assertThrows(RuntimeException.class,()->service.buyNow(1L,request,"buy-now-key-123456",PaymentMethod.COD,"127.0.0.1"));verify(orders,never()).saveAndFlush(any());verifyNoInteractions(payments,carts,cartItems);}
 @Test void inactiveProductCannotBePurchased(){product.setStatus("INACTIVE");assertThrows(RuntimeException.class,()->service.buyNow(1L,request,"buy-now-key-123456",PaymentMethod.COD,"127.0.0.1"));verify(payments,never()).save(any());}
 @Test void replayDoesNotDecrementStockAgain(){service.buyNow(1L,request,"buy-now-key-123456",PaymentMethod.COD,"127.0.0.1");when(orders.findLockedByUser_UserIdAndCheckoutKey(1L,"buy-now-key-123456")).thenReturn(Optional.of(saved));when(payments.findLockedByOrder_OrderId(7L)).thenReturn(Optional.of(payment));when(items.findByOrder_OrderId(7L)).thenReturn(List.of());assertTrue(service.buyNow(1L,request,"buy-now-key-123456",PaymentMethod.COD,"127.0.0.1").replayed());assertEquals(7L,product.getStockQuantity());verify(orders,times(1)).saveAndFlush(any());verifyNoInteractions(carts,cartItems);}
 @Test void hashBindsProductQuantityAndMethod(){String h=CheckoutIdentity.buyNowHash(shipping,PaymentMethod.COD,17L,3L);assertNotEquals(h,CheckoutIdentity.hash(shipping,PaymentMethod.COD));assertNotEquals(h,CheckoutIdentity.buyNowHash(shipping,PaymentMethod.COD,18L,3L));assertNotEquals(h,CheckoutIdentity.buyNowHash(shipping,PaymentMethod.COD,17L,2L));assertNotEquals(h,CheckoutIdentity.buyNowHash(shipping,PaymentMethod.ONLINE,17L,3L));}
 @Test void directCheckoutRequiresKey(){assertThrows(RuntimeException.class,()->service.buyNow(1L,request,null,PaymentMethod.COD,"127.0.0.1"));verifyNoInteractions(carts,cartItems,payments);}
 @Test void differentQuantityCannotReuseCheckoutKey(){service.buyNow(1L,request,"buy-now-key-123456",PaymentMethod.COD,"127.0.0.1");when(orders.findLockedByUser_UserIdAndCheckoutKey(1L,"buy-now-key-123456")).thenReturn(Optional.of(saved));assertThrows(vn.teasmart.backend.exception.PaymentException.class,()->service.buyNow(1L,new BuyNowRequest(17L,2L,shipping),"buy-now-key-123456",PaymentMethod.COD,"127.0.0.1"));assertEquals(7L,product.getStockQuantity());verify(orders,times(1)).saveAndFlush(any());}
 @Test void legacyCartCheckoutStillClearsOnlyItsItems(){Cart cart=new Cart();cart.setCartId(8L);CartItem item=new CartItem();item.setProduct(product);item.setQuantity(2L);when(carts.findByUser_UserId(1L)).thenReturn(Optional.of(cart));when(cartItems.findByCart_CartId(8L)).thenReturn(List.of(item));var result=service.checkout(1L,shipping,"cart-test-key-12345",PaymentMethod.COD,"127.0.0.1");assertEquals(new BigDecimal("200.00"),result.order().totalAmount());assertEquals(8L,product.getStockQuantity());verify(cartItems).deleteAll(List.of(item));}

 private SelectedCheckoutRequest selection(Long id,Long qty){return new SelectedCheckoutRequest(shipping,List.of(new SelectedCheckoutRequest.Item(id,qty)));}
 private void selectedCart(){Cart c=new Cart();c.setCartId(8L);CartItem a=new CartItem();a.setCartItemId(11L);a.setProduct(product);a.setQuantity(2L);CartItem b=new CartItem();b.setCartItemId(12L);b.setQuantity(1L);when(carts.findByUser_UserId(1L)).thenReturn(Optional.of(c));when(cartItems.findByCart_CartId(8L)).thenReturn(List.of(a,b));}
 @Test void selectedKeepsUnselectedCartItem(){selectedCart();var result=service.selected(1L,selection(11L,2L),"selected-key-123456",PaymentMethod.COD,"127.0.0.1");assertEquals(new BigDecimal("200.00"),result.order().totalAmount());assertEquals(8L,product.getStockQuantity());verify(cartItems).deleteAll(argThat(rows->{var list=(List<CartItem>)rows;return list.size()==1&&list.get(0).getCartItemId()==11L;}));}
 @Test void selectedRejectsForeignAndChangedQuantity(){selectedCart();assertThrows(RuntimeException.class,()->service.selected(1L,selection(99L,1L),"selected-key-123456",PaymentMethod.COD,"127.0.0.1"));assertThrows(RuntimeException.class,()->service.selected(1L,selection(11L,3L),"selected-key-123456",PaymentMethod.COD,"127.0.0.1"));verify(orders,never()).saveAndFlush(any());}
 @Test void selectedHashIsOrderedAndRejectsDuplicates(){var a=new SelectedCheckoutRequest.Item(11L,2L);var b=new SelectedCheckoutRequest.Item(12L,1L);assertEquals(CheckoutIdentity.selectedHash(new SelectedCheckoutRequest(shipping,List.of(a,b)),PaymentMethod.COD),CheckoutIdentity.selectedHash(new SelectedCheckoutRequest(shipping,List.of(b,a)),PaymentMethod.COD));assertThrows(RuntimeException.class,()->CheckoutIdentity.selectedHash(new SelectedCheckoutRequest(shipping,List.of(a,a)),PaymentMethod.COD));assertThrows(RuntimeException.class,()->CheckoutIdentity.selectedHash(new SelectedCheckoutRequest(shipping,List.of()),PaymentMethod.COD));}
 @Test void selectedReplayAfterCartRemoval(){selectedCart();var r=selection(11L,2L);service.selected(1L,r,"selected-key-123456",PaymentMethod.COD,"127.0.0.1");when(orders.findLockedByUser_UserIdAndCheckoutKey(1L,"selected-key-123456")).thenReturn(Optional.of(saved));when(payments.findLockedByOrder_OrderId(7L)).thenReturn(Optional.of(payment));when(items.findByOrder_OrderId(7L)).thenReturn(List.of());assertTrue(service.selected(1L,r,"selected-key-123456",PaymentMethod.COD,"127.0.0.1").replayed());assertThrows(RuntimeException.class,()->service.selected(1L,selection(12L,1L),"selected-key-123456",PaymentMethod.COD,"127.0.0.1"));verify(cartItems,times(1)).findByCart_CartId(8L);assertEquals(8L,product.getStockQuantity());}

 @Test void selectedACLeavesB(){selectedCart();CartItem a=cartItems.findByCart_CartId(8L).get(0),b=cartItems.findByCart_CartId(8L).get(1);Product c=new Product();c.setProductId(18L);c.setName("C");c.setPrice(new BigDecimal("50.00"));c.setStockQuantity(5L);c.setStatus("ACTIVE");c.setCategory(product.getCategory());c.setRegion(product.getRegion());c.setStore(product.getStore());when(products.findLockedByProductId(18L)).thenReturn(Optional.of(c));CartItem ci=new CartItem();ci.setCartItemId(13L);ci.setProduct(c);ci.setQuantity(1L);when(cartItems.findByCart_CartId(8L)).thenReturn(List.of(a,b,ci));var request=new SelectedCheckoutRequest(shipping,List.of(new SelectedCheckoutRequest.Item(11L,2L),new SelectedCheckoutRequest.Item(13L,1L)));var result=service.selected(1L,request,"selected-ac-key-1234",PaymentMethod.COD,"127.0.0.1");assertEquals(new BigDecimal("250.00"),result.order().totalAmount());assertEquals(8L,product.getStockQuantity());assertEquals(4L,c.getStockQuantity());verify(cartItems).deleteAll(List.of(a,ci));verify(items,times(2)).save(any());}
}
