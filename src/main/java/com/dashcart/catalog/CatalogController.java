package com.dashcart.catalog;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.dashcart.catalog.CatalogDtos.CategoryRequest;
import com.dashcart.catalog.CatalogDtos.CategoryResponse;
import com.dashcart.catalog.CatalogDtos.ProductRequest;
import com.dashcart.catalog.CatalogDtos.ProductResponse;
import com.dashcart.catalog.CatalogDtos.StoreProductResponse;
import com.dashcart.common.PageResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    // ---- Public browsing ----

    @GetMapping("/categories")
    public List<CategoryResponse> categories() {
        return catalogService.listCategories();
    }

    @GetMapping("/stores/{storeId}/products")
    public PageResponse<StoreProductResponse> storeProducts(
            @PathVariable Long storeId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return catalogService.storeProducts(storeId, categoryId, q, page, size);
    }

    @GetMapping("/stores/{storeId}/products/{productId}")
    public StoreProductResponse storeProduct(@PathVariable Long storeId, @PathVariable Long productId) {
        return catalogService.storeProduct(storeId, productId);
    }

    // ---- Admin ----

    @PostMapping("/admin/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse createCategory(@Valid @RequestBody CategoryRequest request) {
        return catalogService.createCategory(request.name());
    }

    @PostMapping("/admin/products")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse createProduct(@Valid @RequestBody ProductRequest request) {
        return catalogService.createProduct(request);
    }

    @PutMapping("/admin/products/{productId}")
    public ProductResponse updateProduct(@PathVariable Long productId, @Valid @RequestBody ProductRequest request) {
        return catalogService.updateProduct(productId, request);
    }
}
