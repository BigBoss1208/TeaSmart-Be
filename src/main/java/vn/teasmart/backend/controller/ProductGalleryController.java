package vn.teasmart.backend.controller;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import vn.teasmart.backend.service.ProductGalleryService;
@RestController
public class ProductGalleryController {
 private final ProductGalleryService service;public ProductGalleryController(ProductGalleryService service){this.service=service;}
 @GetMapping("/api/products/{id}/images") public ProductGalleryService.Gallery publicImages(@PathVariable Long id){return service.get(id,false);}
 @GetMapping("/api/admin/products/{id}/images") public ProductGalleryService.Gallery adminImages(@PathVariable Long id){return service.get(id,true);}
 public record Images(List<String> urls){}
 @PutMapping("/api/admin/products/{id}/images") public ProductGalleryService.Gallery save(@PathVariable Long id,@RequestBody Images images){return service.save(id,images.urls());}
}
