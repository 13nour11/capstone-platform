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

/** Real PostgreSQL: Flyway V1 + V2 run, Hibernate validates the mapping, the projection joins the category. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ProductRepositoryIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ProductRepository products;

    @Test
    void shouldReturnCategoryName_whenLoadingProductView() {
        ProductView view = products.findViewById(1L).orElseThrow();

        assertThat(view.getName()).isEqualTo("Wireless Mouse");
        assertThat(view.getCategoryName()).isEqualTo("Electronics");
        assertThat(view.getPrice()).isEqualByComparingTo("24.99");
    }

    @Test
    void shouldReturnEmpty_whenProductDoesNotExist() {
        assertThat(products.findViewById(999L)).isEmpty();
    }

    @Test
    void shouldPageAndSort_whenListingViews() {
        Page<ProductView> page = products.findAllViews(PageRequest.of(0, 3, Sort.by(Sort.Direction.DESC, "price")));

        assertThat(page.getTotalElements()).isEqualTo(10);
        assertThat(page.getContent()).extracting(ProductView::getName)
                .containsExactly("27-inch Monitor", "Office Chair", "Mechanical Keyboard");
        assertThat(page.getContent()).allSatisfy(view -> assertThat(view.getCategoryName()).isNotBlank());
    }
}
