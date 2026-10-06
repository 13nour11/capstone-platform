package com.ecommerce.product.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ecommerce.product.domain.ProductView;

/** Real PostgreSQL: Flyway V1-V3 run, Hibernate validates the mapping, the projection joins the category. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ProductRepositoryIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ProductRepository products;

    @Autowired
    private ProductRatingRepository ratings;

    @Test
    void shouldReturnCategoryName_whenLoadingProductView() {
        ProductView view = products.findViewById(1L, "tenant-a").orElseThrow();

        assertThat(view.getName()).isEqualTo("Wireless Mouse");
        assertThat(view.getCategoryName()).isEqualTo("Electronics");
        assertThat(view.getPrice()).isEqualByComparingTo("24.99");
    }

    @Test
    void shouldReturnEmpty_whenProductDoesNotExist() {
        assertThat(products.findViewById(999L, "tenant-a")).isEmpty();
    }

    @Test
    void shouldPageAndSort_whenListingViews() {
        Page<ProductView> page = products.findAllViews("tenant-a", PageRequest.of(0, 3, Sort.by(Sort.Direction.DESC, "price")));

        assertThat(page.getTotalElements()).isEqualTo(10);
        assertThat(page.getContent()).extracting(ProductView::getName)
                .containsExactly("27-inch Monitor", "Office Chair", "Mechanical Keyboard");
        assertThat(page.getContent()).allSatisfy(view -> assertThat(view.getCategoryName()).isNotBlank());
    }

    // --- Bonus B3: tenant isolation at the query level ---

    @Test
    void shouldNotFindOtherTenantsProduct_whenLoadingById() {
        long tenantBProduct = products.findAllViews("tenant-b", PageRequest.of(0, 1)).getContent().get(0).getId();

        assertThat(products.findViewById(tenantBProduct, "tenant-b")).isPresent();
        assertThat(products.findViewById(tenantBProduct, "tenant-a")).isEmpty();
        assertThat(products.findByIdAndTenantId(tenantBProduct, "tenant-a")).isEmpty();
    }

    @Test
    void shouldListOnlyOwnTenantsProducts() {
        assertThat(products.findAllViews("tenant-b", PageRequest.of(0, 50)).getContent())
                .extracting(ProductView::getName)
                .containsExactlyInAnyOrder("Gaming Headset", "Refactoring", "Standing Desk");
        assertThat(products.findAllViews("tenant-a", PageRequest.of(0, 50)).getContent())
                .extracting(ProductView::getName)
                .doesNotContain("Gaming Headset", "Refactoring", "Standing Desk");
    }

    // --- Bonus B1: rating read model ---

    @Test
    void shouldAverageRatings_andIgnoreDuplicateReview() {
        assertThat(ratings.insertIfAbsent("review-1", 5L, 5)).isEqualTo(1);
        assertThat(ratings.insertIfAbsent("review-2", 5L, 2)).isEqualTo(1);
        assertThat(ratings.insertIfAbsent("review-1", 5L, 5)).isZero();

        ProductView view = products.findViewById(5L, "tenant-a").orElseThrow();

        assertThat(view.getRatingCount()).isEqualTo(2);
        assertThat(view.getAverageRating()).isEqualTo(3.5);
    }

    @Test
    void shouldReportNoRatings_whenProductHasNone() {
        ProductView view = products.findViewById(6L, "tenant-a").orElseThrow();

        assertThat(view.getRatingCount()).isZero();
        assertThat(view.getAverageRating()).isNull();
    }
}
