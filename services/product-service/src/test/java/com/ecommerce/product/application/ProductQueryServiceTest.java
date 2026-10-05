package com.ecommerce.product.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.ecommerce.product.domain.ProductNotFoundException;
import com.ecommerce.product.domain.ProductView;
import com.ecommerce.product.infrastructure.ProductRepository;

class ProductQueryServiceTest {

    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductQueryService service = new ProductQueryService(products);

    @Test
    void shouldMapProjection_whenProductExists() {
        ProductView view = view(5L, "Clean Code", "Books");
        given(products.findViewById(5L)).willReturn(Optional.of(view));

        ProductDetails details = service.getById(5L);

        assertThat(details).isEqualTo(
                new ProductDetails(5L, "Clean Code", "desc", new BigDecimal("34.90"), 2L, "Books"));
    }

    @Test
    void shouldThrowNotFound_whenProductIsMissing() {
        given(products.findViewById(5L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(5L))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining("5");
    }

    @Test
    void shouldReturnPageMetadata_whenListing() {
        Pageable pageable = PageRequest.of(1, 2);
        ProductView hub = view(3L, "USB-C Hub", "Electronics");
        given(products.findAllViews(pageable)).willReturn(new PageImpl<>(List.of(hub), pageable, 5));

        PageResult<ProductDetails> page = service.list(pageable);

        assertThat(page.content()).extracting(ProductDetails::name).containsExactly("USB-C Hub");
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    private static ProductView view(long id, String name, String categoryName) {
        ProductView view = mock(ProductView.class);
        given(view.getId()).willReturn(id);
        given(view.getName()).willReturn(name);
        given(view.getDescription()).willReturn("desc");
        given(view.getPrice()).willReturn(new BigDecimal("34.90"));
        given(view.getCategoryId()).willReturn(2L);
        given(view.getCategoryName()).willReturn(categoryName);
        return view;
    }
}
