package com.ecommerce.product.application;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ecommerce.product.domain.Category;
import com.ecommerce.product.domain.CategoryNotFoundException;
import com.ecommerce.product.domain.Product;
import com.ecommerce.product.domain.ProductNotFoundException;
import com.ecommerce.product.infrastructure.CacheConfig;
import com.ecommerce.product.infrastructure.CategoryRepository;
import com.ecommerce.product.infrastructure.ProductRepository;

/** Catalogue writes. Every write evicts the item and all cached pages (FR-15). */
@Service
@Transactional
public class ProductCommandService {

    private final ProductRepository products;
    private final CategoryRepository categories;

    public ProductCommandService(ProductRepository products, CategoryRepository categories) {
        this.products = products;
        this.categories = categories;
    }

    @CacheEvict(cacheNames = CacheConfig.PRODUCTS, allEntries = true)
    public ProductDetails create(String tenant, ProductCommand command) {
        Product product = new Product(command.name(), command.description(), command.price(),
                category(command.categoryId()), tenant);
        return ProductDetails.from(products.save(product));
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.PRODUCT, key = "#tenant + ':' + #id"),
            @CacheEvict(cacheNames = CacheConfig.PRODUCTS, allEntries = true)
    })
    public ProductDetails update(String tenant, long id, ProductCommand command) {
        Product product = owned(tenant, id);
        product.changeDetails(command.name(), command.description(), command.price(), category(command.categoryId()));
        return ProductDetails.from(product);
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.PRODUCT, key = "#tenant + ':' + #id"),
            @CacheEvict(cacheNames = CacheConfig.PRODUCTS, allEntries = true)
    })
    public void delete(String tenant, long id) {
        Product product = owned(tenant, id);
        products.delete(product);
    }

    /** B3: a product of another tenant is reported as missing (404), never as forbidden, so ids leak nothing. */
    private Product owned(String tenant, long id) {
        return products.findByIdAndTenantId(id, tenant).orElseThrow(() -> new ProductNotFoundException(id));
    }

    private Category category(long categoryId) {
        return categories.findById(categoryId).orElseThrow(() -> new CategoryNotFoundException(categoryId));
    }
}
