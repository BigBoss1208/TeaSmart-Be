package vn.teasmart.backend.seller;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import vn.teasmart.backend.storage.*;
import vn.teasmart.backend.exception.ResourceNotFoundException;

@RestController
@ConditionalOnProperty(name="teasmart.seller.enabled",havingValue="true")
public class SellerLogoController {
    private final SellerService service;
    private final ReviewImageValidator validator;
    private final ReviewImageStorage storage;
    public SellerLogoController(SellerService service,ReviewImageValidator validator,ReviewImageStorage storage) { this.service=service;this.validator=validator;this.storage=storage; }
    @PostMapping(value="/api/seller/shop/logo",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Shop upload(@AuthenticationPrincipal Jwt jwt,@RequestPart("file") MultipartFile file) {
        Long owner=Long.valueOf(jwt.getSubject());
        service.myShop(owner);
        var image=validator.normalize(List.of(file)).get(0);
        String key=storage.stage(image.bytes(),image.extension());
        try {
            // setLogo serializes with shop suspension/profile updates using the owner lock.
            String old=service.lockMyShop(owner).logoUrl();
            Shop updated=service.setLogo(owner,"/api/shop-logos/"+key);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { if(old!=null && old.startsWith("/api/shop-logos/"))storage.deleteAfterCommit(old.substring("/api/shop-logos/".length())); }
                @Override public void afterCompletion(int status) { if(status!=STATUS_COMMITTED)storage.discard(key); }
            });
            storage.finalizeFile(key);
            return updated;
        } catch(RuntimeException e) {storage.discard(key);throw e;}
    }
    @GetMapping("/api/shop-logos/{key}") public ResponseEntity<byte[]> read(@PathVariable String key) {
        if(!key.matches("[0-9a-f-]{36}\\.(png|jpg)") || !service.referencesLogo("/api/shop-logos/"+key))throw new ResourceNotFoundException("Logo not found.");
        return ResponseEntity.ok().contentType(key.endsWith(".png")?MediaType.IMAGE_PNG:MediaType.IMAGE_JPEG)
            .header("X-Content-Type-Options","nosniff").cacheControl(CacheControl.noCache()).body(storage.read(key));
    }
}
