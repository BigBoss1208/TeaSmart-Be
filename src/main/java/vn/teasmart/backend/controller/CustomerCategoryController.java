package vn.teasmart.backend.controller;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.teasmart.backend.dto.response.CategoryResponse;
import vn.teasmart.backend.service.CategoryService;

@RestController
@RequestMapping("/api/categories")
public class CustomerCategoryController {

    private final CategoryService service;

    public CustomerCategoryController(CategoryService service) {
        this.service = service;
    }

    @GetMapping
    public List<CategoryResponse> listCategories() {
        return service.listPublicCategories();
    }
}
