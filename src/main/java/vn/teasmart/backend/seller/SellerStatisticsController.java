package vn.teasmart.backend.seller;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
@RestController
@ConditionalOnProperty(name="teasmart.seller.foundation-enabled",havingValue="true")
public class SellerStatisticsController {
 private final SellerStatisticsService service;
 public SellerStatisticsController(SellerStatisticsService service){this.service=service;}
 @GetMapping("/api/seller/statistics") public SellerStatisticsService.Report get(@AuthenticationPrincipal Jwt jwt,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to,@RequestParam Map<String,String> params){
  if(!Set.of("from","to").containsAll(params.keySet()))throw new IllegalArgumentException("Unsupported statistics filter.");
  return service.get(Long.parseLong(jwt.getSubject()),from,to);
 }
}
