package vn.teasmart.backend.payment;
import org.junit.jupiter.api.Test;
import vn.teasmart.backend.config.VnpaySettings;
import vn.teasmart.backend.service.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class VnpayQueryDiagnosticsTest {
    private String outcome(String body) throws Exception {
        var settings = new VnpaySettings(true,"TESTONLY",UUID.randomUUID().toString(),"http://localhost:3000/payment/vnpay-return",15);
        var client = new VnpayQueryClient(settings,new VnpayGateway(settings));
        var method = VnpayQueryClient.class.getDeclaredMethod("parseResponseDiagnostic",byte[].class,String.class);
        method.setAccessible(true);
        var result = method.invoke(client,body.getBytes(StandardCharsets.UTF_8),"test-reference");
        var outcome = result.getClass().getDeclaredMethod("outcome"); outcome.setAccessible(true);
        var notification = result.getClass().getDeclaredMethod("notification"); notification.setAccessible(true);
        assertNull(notification.invoke(result),"Unverified responses must never authorize a financial transition");
        return (String) outcome.invoke(result);
    }
    @Test void unsigned99IsMissingSignatureNotCryptographicMismatch() throws Exception {
        assertEquals("SIGNATURE_MISSING",outcome("{\"vnp_ResponseCode\":\"99\",\"vnp_Message\":\"Unknown error\"}"));
        assertEquals("SIGNATURE_MISSING",outcome("{\"vnp_SecureHash\":\"\"}"));
    }
    @Test void malformedAndWrongSignaturesAreDistinctAndNonAuthoritative() throws Exception {
        assertEquals("SIGNATURE_MALFORMED",outcome("{\"vnp_SecureHash\":123}"));
        assertEquals("SIGNATURE_MALFORMED",outcome("{\"vnp_SecureHash\":\"bad\"}"));
        assertEquals("SIGNATURE_REJECTED",outcome("{\"vnp_SecureHash\":\""+"0".repeat(128)+"\"}"));
    }
}