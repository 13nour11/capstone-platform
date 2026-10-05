package com.ecommerce.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.ecommerce.product.application.RecordRatingService;
import com.ecommerce.product.domain.ProductNotFoundException;
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
    private static final String FIRST_PAGE_KEY = "tenant-a:0:20:id: ASC";

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
        ProductDetails first = queries.getById("tenant-a", 2L);

        assertThat(itemCache.get("tenant-a:2", ProductDetails.class)).isEqualTo(first);
        assertThat(queries.getById("tenant-a", 2L)).isEqualTo(first);
    }

    @Test
    void shouldCachePage_whenListing() {
        PageResult<ProductDetails> page = queries.list("tenant-a", FIRST_PAGE);

        assertThat(pageCache.get(FIRST_PAGE_KEY, PageResult.class)).isEqualTo(page);
    }

    @Test
    void shouldEvictItemAndPages_whenProductIsUpdated() {
        queries.getById("tenant-a", 3L);
        queries.list("tenant-a", FIRST_PAGE);

        commands.update("tenant-a", 3L, new ProductCommand("USB-C Hub Pro", "8-in-1", new BigDecimal("49.00"), 1L));

        assertThat(itemCache.get("tenant-a:3")).isNull();
        assertThat(pageCache.get(FIRST_PAGE_KEY)).isNull();
        assertThat(queries.getById("tenant-a", 3L).name()).isEqualTo("USB-C Hub Pro");
    }

    @Test
    void shouldEvictPages_whenProductIsCreated() {
        queries.list("tenant-a", FIRST_PAGE);

        ProductDetails created = commands.create("tenant-a", new ProductCommand("Notebook", null, new BigDecimal("3.50"), 2L));

        assertThat(pageCache.get(FIRST_PAGE_KEY)).isNull();
        assertThat(created.categoryName()).isEqualTo("Books");
    }

    @Test
    void shouldEvictItem_whenProductIsDeleted() {
        ProductDetails created = commands.create("tenant-a", new ProductCommand("Pen", null, new BigDecimal("1.20"), 2L));
        queries.getById("tenant-a", created.id());

        commands.delete("tenant-a", created.id());

        assertThat(itemCache.get("tenant-a:" + created.id())).isNull();
    }

    // --- Bonus B3: tenant A can neither read nor change tenant B's products, through the cache or the database ---

    @Test
    void shouldHideOtherTenantsProduct_evenWhenItIsCached() {
        long tenantBProduct = queries.list("tenant-b", FIRST_PAGE).content().get(0).id();
        queries.getById("tenant-b", tenantBProduct);

        assertThat(itemCache.get("tenant-b:" + tenantBProduct)).isNotNull();
        assertThatThrownBy(() -> queries.getById("tenant-a", tenantBProduct))
                .isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void shouldRejectUpdateAndDelete_ofOtherTenantsProduct() {
        long tenantBProduct = queries.list("tenant-b", FIRST_PAGE).content().get(0).id();
        ProductCommand change = new ProductCommand("Hijacked", null, new BigDecimal("1.00"), 1L);

        assertThatThrownBy(() -> commands.update("tenant-a", tenantBProduct, change))
                .isInstanceOf(ProductNotFoundException.class);
        assertThatThrownBy(() -> commands.delete("tenant-a", tenantBProduct))
                .isInstanceOf(ProductNotFoundException.class);
        assertThat(queries.getById("tenant-b", tenantBProduct).name()).isNotEqualTo("Hijacked");
    }

    @Test
    void shouldStampTenant_whenCreating() {
        ProductDetails created = commands.create("tenant-b", new ProductCommand("Tenant B Mug", null, new BigDecimal("8.00"), 3L));

        assertThat(queries.getById("tenant-b", created.id()).name()).isEqualTo("Tenant B Mug");
        assertThatThrownBy(() -> queries.getById("tenant-a", created.id()))
                .isInstanceOf(ProductNotFoundException.class);
    }

    // --- Bonus B1: a new rating evicts the cached product, so the average shows up on the next read ---

    @Autowired
    private RecordRatingService ratings;

    @Test
    void shouldShowNewRating_whenReviewIsRecorded() {
        queries.getById("tenant-a", 7L);

        assertThat(ratings.record("cache-review-1", 7L, 4)).isTrue();
        assertThat(ratings.record("cache-review-1", 7L, 4)).isFalse();

        assertThat(itemCache.get("tenant-a:7")).isNull();
        ProductDetails details = queries.getById("tenant-a", 7L);
        assertThat(details.ratingCount()).isEqualTo(1);
        assertThat(details.averageRating()).isEqualTo(4.0);
    }
}
