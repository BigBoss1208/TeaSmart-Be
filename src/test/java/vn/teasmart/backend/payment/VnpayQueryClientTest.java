package vn.teasmart.backend.payment;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import vn.teasmart.backend.config.VnpaySettings;
import vn.teasmart.backend.enums.PaymentStatus;
import vn.teasmart.backend.service.*;
import static org.junit.jupiter.api.Assertions.*;

class VnpayQueryClientTest {
    private final VnpaySettings settings = new VnpaySettings(true,"TESTONLY",UUID.randomUUID().toString(),"http://localhost:3000/payment/vnpay-return",15);
    private final VnpayGateway gateway = new VnpayGateway(settings);
    private final VnpayQueryClient client = new VnpayQueryClient(settings,gateway);
    private final PaymentService.Snapshot snapshot = new PaymentService.Snapshot(1L,"TSVNPtest",PaymentTime.now(),PaymentStatus.PENDING);
    private final JsonMapper json = JsonMapper.builder().build();
    private static final List<String> FIELDS = List.of("vnp_ResponseId","vnp_Command","vnp_ResponseCode","vnp_Message","vnp_TmnCode",
            "vnp_TxnRef","vnp_Amount","vnp_BankCode","vnp_PayDate","vnp_TransactionNo","vnp_TransactionType","vnp_TransactionStatus",
            "vnp_OrderInfo","vnp_PromotionCode","vnp_PromotionAmount");
    private Map<String,String> data() {
        Map<String,String> m = new LinkedHashMap<>(); for(String f:FIELDS)m.put(f,"");
        m.put("vnp_ResponseId","test");m.put("vnp_Command","querydr");m.put("vnp_ResponseCode","00");m.put("vnp_Message","Success");
        m.put("vnp_TmnCode","TESTONLY");m.put("vnp_TxnRef",snapshot.reference());m.put("vnp_Amount","10000000");
        m.put("vnp_TransactionNo","1234");m.put("vnp_TransactionType","01");m.put("vnp_TransactionStatus","00");
        m.put("vnp_PayDate",VnpayGateway.DATE.format(PaymentTime.now())); return m;
    }
    private byte[] signed(Map<String,String> m) {
        m.put("vnp_SecureHash",gateway.sign(FIELDS.stream().map(m::get).collect(java.util.stream.Collectors.joining("|"))));
        return json.writeValueAsBytes(m);
    }
    @Test void signedSuccessfulQueryProvidesAuthoritativeTransaction() {
        var r=client.parseResponse(signed(data()),snapshot);assertNotNull(r);assertTrue(r.success());assertEquals(new BigDecimal("100000.00"),r.amount());
    }
    @Test void querySuccessDoesNotImplyPaymentSuccess() {
        var m=data();m.put("vnp_TransactionStatus","01");assertNull(client.parseResponse(signed(m),snapshot));
        m=data();m.put("vnp_ResponseCode","91");assertNull(client.parseResponse(signed(m),snapshot));
    }
    @Test void invalidSignatureMerchantReferenceOrTransactionTypeIsRejected() {
        for(String key:List.of("vnp_TmnCode","vnp_TxnRef","vnp_TransactionType")) {
            var m=data();m.put(key,"wrong");assertNull(client.parseResponse(signed(m),snapshot));
        }
        var m=data();m.put("vnp_SecureHash","invalid");assertNull(client.parseResponse(json.writeValueAsBytes(m),snapshot));
    }
    @Test void malformedOversizedDuplicateAndWrongJsonTypesRejected() {
        assertNull(client.parseResponse("invalid-json".getBytes(),snapshot));assertNull(client.parseResponse(new byte[65537],snapshot));
        String body=new String(signed(data()),java.nio.charset.StandardCharsets.UTF_8);
        assertNull(client.parseResponse(body.replaceFirst("\\{","{\"vnp_TmnCode\":\"duplicate\",").getBytes(),snapshot));
        var m=new LinkedHashMap<String,Object>(data());m.put("vnp_Amount",100);assertNull(client.parseResponse(json.writeValueAsBytes(m),snapshot));
    }
    @Test void networkCallCannotRunInsideDatabaseTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {assertThrows(IllegalStateException.class,()->client.query(snapshot));}
        finally {TransactionSynchronizationManager.setActualTransactionActive(false);}
    }
}
