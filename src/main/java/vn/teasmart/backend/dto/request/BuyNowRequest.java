package vn.teasmart.backend.dto.request;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
public record BuyNowRequest(@NotNull @Positive Long productId, @NotNull @Positive Long quantity,
        @NotNull @Valid PlaceOrderRequest shipping) { }
