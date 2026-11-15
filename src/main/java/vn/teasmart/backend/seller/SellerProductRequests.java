package vn.teasmart.backend.seller;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
public final class SellerProductRequests {
 private SellerProductRequests() {}
 public record Product(@NotNull @Positive Long categoryId,@NotNull @Positive Long regionId,
   @NotBlank @Size(max=200) String name,@Size(max=10000) String description,
   @NotNull @DecimalMin(value="0",inclusive=false) @Digits(integer=10,fraction=2) BigDecimal price,
   @NotNull @Min(1) @Max(4294967295L) Long weightGrams,@Size(max=500) String imageUrl) {
   public Product {name=name==null?null:name.trim();}
   @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("Unsupported Seller product field.");}
 }
 public record Status(@NotNull @Pattern(regexp="ACTIVE|INACTIVE") String status) {
   @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("Unsupported field.");}
 }
 public record Stock(@NotNull @Min(-4294967295L) @Max(4294967295L) Long delta,@NotBlank @Size(max=500) String reason) {
   public Stock {reason=reason==null?null:reason.trim();}
   @JsonAnySetter public void unknown(String name,Object value){throw new IllegalArgumentException("Unsupported field.");}
 }
}
