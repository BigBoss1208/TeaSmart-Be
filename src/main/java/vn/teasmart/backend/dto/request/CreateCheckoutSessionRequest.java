package vn.teasmart.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateCheckoutSessionRequest(
        @NotBlank @Size(max = 20) String sourceType,
        @NotNull @Valid PlaceOrderRequest shipping,
        @Positive Long buyNowProductId,
        @Positive Long buyNowQuantity,
        @Size(max = 100) List<SelectedCheckoutRequest.Item> selectedItems,
        @Size(max = 100) List<@NotNull @Valid BuyAgainItem> buyAgainItems,
        @Size(max = 36) String independentPurchaseIntentId) {
    public CreateCheckoutSessionRequest(String sourceType, PlaceOrderRequest shipping, Long buyNowProductId,
            Long buyNowQuantity, List<SelectedCheckoutRequest.Item> selectedItems) {
        this(sourceType, shipping, buyNowProductId, buyNowQuantity, selectedItems, null, null);
    }

    public CreateCheckoutSessionRequest(String sourceType, PlaceOrderRequest shipping, Long buyNowProductId,
            Long buyNowQuantity, List<SelectedCheckoutRequest.Item> selectedItems,
            List<BuyAgainItem> buyAgainItems) {
        this(sourceType, shipping, buyNowProductId, buyNowQuantity, selectedItems, buyAgainItems, null);
    }

    public record BuyAgainItem(@NotNull @Positive Long productId, @NotNull @Positive Long quantity) { }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Unknown checkout session field.");
    }
}
