package vn.teasmart.backend.customer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import vn.teasmart.backend.dto.request.AddressRequest;

class AddressRequestTest {
    @Test
    void districtIsOptionalForNewTwoLevelAddressesAndLegacyValuesRemainAccepted() {
        var request = new AddressRequest("Người nhận", "0912345678", "Tỉnh Thái Nguyên", null,
                "Xã Tân Cương", "Số 1 đường Trà");
        var legacy = new AddressRequest("Người nhận", "0912345678", "Thái Nguyên", "Thành phố Thái Nguyên",
                "Phường Trưng Vương", "Số 2 đường Trà");

        assertEquals("", request.district());
        assertEquals("Thành phố Thái Nguyên", legacy.district());
        try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(validatorFactory.getValidator().validate(request).isEmpty());
            assertTrue(validatorFactory.getValidator().validate(legacy).isEmpty());
        }
    }

    @Test
    void omittedDistrictInJsonIsNormalizedToAnEmptyString() throws Exception {
        var json = JsonMapper.builder().build();
        var request = json.readValue("""
                {"recipientName":"Người nhận","phone":"0912345678","province":"Tỉnh Thái Nguyên",
                 "ward":"Xã Tân Cương","detailAddress":"Số 1 đường Trà"}
                """, AddressRequest.class);

        assertEquals("", request.district());
        assertTrue(Validation.buildDefaultValidatorFactory().getValidator().validate(request).isEmpty());
    }
}
