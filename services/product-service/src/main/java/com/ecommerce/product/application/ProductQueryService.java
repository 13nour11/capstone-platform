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

    @Cacheable(cacheNames = CacheConfig.PRODUCT, key = "#id")
    public ProductDetails getById(long id) {
        return products.findViewById(id)
                .map(ProductDetails::from)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    @Cacheable(cacheNames = CacheConfig.PRODUCTS,
            key = "#pageable.pageNumber + ':' + #pageable.pageSize + ':' + #pageable.sort")
    public PageResult<ProductDetails> list(Pageable pageable) {
        return PageResult.from(products.findAllViews(pageable), ProductDetails::from);
    }
}
