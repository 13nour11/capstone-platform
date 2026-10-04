package com.ecommerce.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ecommerce.product.application.PageResult;
import com.ecommerce.product.application.ProductCommand;
import com.ecommerce.product.application.ProductCommandService;
import com.ecommerce.product.application.ProductDetails;
import com.ecommerce.product.application.ProductQueryService;
import com.ecommerce.product.infrastructure.CacheConfig;

/** FR-15 end to end on real PostgreSQL and Redis: reads are cached, every write evicts item + pages. */
@SpringBootTest
@Testcontainers
class ProductCacheIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final Pageable FIRST_PAGE = PageRequest.of(0, 20, Sort.by("id"));
    private static final String FIRST_PAGE_KEY = "0:20:id: ASC";

    @Autowired
    private ProductQueryService queries;

    @Autowired
    private ProductCommandService commands;

    @Autowired
    private CacheManager cacheManager;

    private Cache itemCache;
    private Cache pageCache;

    @BeforeEach
    void clearCaches() {
        itemCache = cacheManager.getCache(CacheConfig.PRODUCT);
        pageCache = cacheManager.getCache(CacheConfig.PRODUCTS);
        itemCache.clear();
        pageCache.clear();
    }

    @Test
    void shouldServeSecondReadFromRedis_whenItemWasRead() {
        ProductDetails first = queries.getById(2L);

        assertThat(itemCache.get(2L, ProductDetails.class)).isEqualTo(first);
        assertThat(queries.getById(2L)).isEqualTo(first);
    }

    @Test
    void shouldCachePage_whenListing() {
        PageResult<ProductDetails> page = queries.list(FIRST_PAGE);

        assertThat(pageCache.get(FIRST_PAGE_KEY, PageResult.class)).isEqualTo(page);
    }

    @Test
    void shouldEvictItemAndPages_whenProductIsUpdated() {
        queries.getById(3L);
        queries.list(FIRST_PAGE);

        commands.update(3L, new ProductCommand("USB-C Hub Pro", "8-in-1", new BigDecimal("49.00"), 1L));

        assertThat(itemCache.get(3L)).isNull();
        assertThat(pageCache.get(FIRST_PAGE_KEY)).isNull();
        assertThat(queries.getById(3L).name()).isEqualTo("USB-C Hub Pro");
    }

    @Test
    void shouldEvictPages_whenProductIsCreated() {
        queries.list(FIRST_PAGE);

        ProductDetails created = commands.create(new ProductCommand("Notebook", null, new BigDecimal("3.50"), 2L));

        assertThat(pageCache.get(FIRST_PAGE_KEY)).isNull();
        assertThat(created.categoryName()).isEqualTo("Books");
    }

    @Test
    void shouldEvictItem_whenProductIsDeleted() {
        ProductDetails created = commands.create(new ProductCommand("Pen", null, new BigDecimal("1.20"), 2L));
        queries.getById(created.id());

        commands.delete(created.id());

        assertThat(itemCache.get(created.id())).isNull();
    }
}
