package vn.teasmart.backend.dto.request;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
public record SelectedCheckoutRequest(@NotNull @Valid PlaceOrderRequest shipping, @NotEmpty @Size(max=100) List<@NotNull @Valid Item> items) {
 public record Item(@NotNull @Positive Long cartItemId,@NotNull @Positive Long quantity) {}
}
