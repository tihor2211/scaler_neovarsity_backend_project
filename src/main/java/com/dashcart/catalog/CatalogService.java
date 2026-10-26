package com.dashcart.catalog;

import java.util.List;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.dashcart.catalog.CatalogDtos.CategoryResponse;
import com.dashcart.catalog.CatalogDtos.ProductRequest;
import com.dashcart.catalog.CatalogDtos.ProductResponse;
import com.dashcart.catalog.CatalogDtos.StoreProductResponse;
import com.dashcart.common.ApiException;
import com.dashcart.common.PageResponse;
import com.dashcart.config.CacheConfig;
import com.dashcart.config.CatalogCache;
import com.dashcart.store.StoreService;

@Service
public class CatalogService {

    private final CategoryRepository categories;
    private final ProductRepository products;
    private final StoreService storeService;
    private final CatalogCache cache;

    public CatalogService(CategoryRepository categories, ProductRepository products, StoreService storeService,
            CatalogCache cache) {
        this.categories = categories;
        this.products = products;
        this.storeService = storeService;
        this.cache = cache;
    }

    @Transactional
    public CategoryResponse createCategory(String name) {
        cache.invalidateCategoriesAfterCommit();
        String trimmed = name.trim();
        if (categories.existsByNameIgnoreCase(trimmed)) {
            throw ApiException.conflict("Category '" + trimmed + "' already exists");
        }
        return CategoryResponse.from(categories.save(new Category(trimmed)));
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheConfig.CATEGORIES, key = "'all'")
    public List<CategoryResponse> listCategories() {
        return categories.findAllByOrderByNameAsc().stream().map(CategoryResponse::from).toList();
    }

    @Transactional
    public ProductResponse createProduct(ProductRequest request) {
        cache.invalidateListingsAfterCommit();
        validate(request);
        Product product = new Product(request.categoryId(), request.name().trim(), request.description(),
                request.unit().trim(), request.mrp(), request.price(), request.imageUrl());
        return ProductResponse.from(products.save(product));
    }

    @Transactional
    public ProductResponse updateProduct(Long productId, ProductRequest request) {
        cache.invalidateListingsAfterCommit();
        validate(request);
        Product product = getProduct(productId);
        product.update(request.categoryId(), request.name().trim(), request.description(), request.unit().trim(),
                request.mrp(), request.price(), request.imageUrl(),
                request.active() == null || request.active());
        return ProductResponse.from(product);
    }

    // The busiest read in the app (every customer browsing), so it's cached for 30 s per distinct query.
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheConfig.STORE_PRODUCTS,
            key = "#storeId + ':' + #categoryId + ':' + #search + ':' + #page + ':' + #size")
    public PageResponse<StoreProductResponse> storeProducts(Long storeId, Long categoryId, String search, int page,
            int size) {
        storeService.getActive(storeId);
        // Ordering (by name) is fixed in the query itself.
        var result = products.findStoreProducts(storeId, categoryId, search == null ? "" : search.trim(),
                PageRequest.of(page, size));
        return PageResponse.from(result.map(StoreProductResponse::from));
    }

    @Transactional(readOnly = true)
    public StoreProductResponse storeProduct(Long storeId, Long productId) {
        return products.findStoreProduct(storeId, productId)
                .map(StoreProductResponse::from)
                .orElseThrow(() -> ApiException.notFound("Product " + productId + " isn't sold at store " + storeId));
    }

    @Transactional(readOnly = true)
    public Product getProduct(Long productId) {
        return products.findById(productId)
                .orElseThrow(() -> ApiException.notFound("Product " + productId + " not found"));
    }

    private void validate(ProductRequest request) {
        if (request.price().compareTo(request.mrp()) > 0) {
            throw ApiException.badRequest("Price can't be more than MRP");
        }
        if (!categories.existsById(request.categoryId())) {
            throw ApiException.notFound("Category " + request.categoryId() + " not found");
        }
    }
}
