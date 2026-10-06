package com.ecommerce.product.application;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ecommerce.product.domain.ProductNotFoundException;
import com.ecommerce.product.infrastructure.CacheConfig;
import com.ecommerce.product.infrastructure.ProductRepository;

/** Cache-aside reads (FR-15): Redis first, the projection query on a miss. */
@Service
@Transactional(readOnly = true)
public class ProductQueryService {

    private final ProductRepository products;

    public ProductQueryService(ProductRepository products) {
        this.products = products;
    }

    // Cache keys start with the tenant (B3), so a cached product is never served to another tenant
    @Cacheable(cacheNames = CacheConfig.PRODUCT, key = "#tenant + ':' + #id")
    public ProductDetails getById(String tenant, long id) {
        return products.findViewById(id, tenant)
                .map(ProductDetails::from)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    @Cacheable(cacheNames = CacheConfig.PRODUCTS,
            key = "#tenant + ':' + #pageable.pageNumber + ':' + #pageable.pageSize + ':' + #pageable.sort")
    public PageResult<ProductDetails> list(String tenant, Pageable pageable) {
        return PageResult.from(products.findAllViews(tenant, pageable), ProductDetails::from);
    }
}
