package com.ecommerce.product.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ecommerce.product.domain.Category;
import com.ecommerce.product.domain.CategoryNotFoundException;
import com.ecommerce.product.domain.Product;
import com.ecommerce.product.domain.ProductNotFoundException;
import com.ecommerce.product.infrastructure.CategoryRepository;
import com.ecommerce.product.infrastructure.ProductRepository;

class ProductCommandServiceTest {

    private static final ProductCommand COMMAND =
            new ProductCommand("Desk Lamp", "LED", new BigDecimal("29.99"), 3L);

    private final ProductRepository products = mock(ProductRepository.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final ProductCommandService service = new ProductCommandService(products, categories);

    private final Category home = mock(Category.class);

    @BeforeEach
    void setUp() {
        given(home.getId()).willReturn(3L);
        given(home.getName()).willReturn("Home");
    }

    @Test
    void shouldSaveProductWithCategory_whenCreating() {
        given(categories.findById(3L)).willReturn(Optional.of(home));
        given(products.save(any(Product.class))).willAnswer(returnsFirstArg());

        ProductDetails created = service.create(COMMAND);

        assertThat(created.name()).isEqualTo("Desk Lamp");
        assertThat(created.price()).isEqualByComparingTo("29.99");
        assertThat(created.categoryName()).isEqualTo("Home");
    }

    @Test
    void shouldRejectCreate_whenCategoryDoesNotExist() {
        given(categories.findById(3L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(COMMAND)).isInstanceOf(CategoryNotFoundException.class);
        verify(products, never()).save(any());
    }

    @Test
    void shouldChangeAllDetails_whenUpdating() {
        Category books = mock(Category.class);
        given(books.getName()).willReturn("Books");
        Product existing = new Product("Old name", null, BigDecimal.ONE, books);
        given(products.findById(7L)).willReturn(Optional.of(existing));
        given(categories.findById(3L)).willReturn(Optional.of(home));

        ProductDetails updated = service.update(7L, COMMAND);

        assertThat(updated.name()).isEqualTo("Desk Lamp");
        assertThat(updated.description()).isEqualTo("LED");
        assertThat(updated.categoryName()).isEqualTo("Home");
        assertThat(existing.getPrice()).isEqualByComparingTo("29.99");
    }

    @Test
    void shouldThrowNotFound_whenUpdatingMissingProduct() {
        given(products.findById(7L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(7L, COMMAND)).isInstanceOf(ProductNotFoundException.class);
    }

    @Test
    void shouldDeleteProduct_whenItExists() {
        Product existing = new Product("Lamp", null, BigDecimal.ONE, home);
        given(products.findById(7L)).willReturn(Optional.of(existing));

        service.delete(7L);

        verify(products).delete(existing);
    }

    @Test
    void shouldThrowNotFound_whenDeletingMissingProduct() {
        given(products.findById(7L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(7L)).isInstanceOf(ProductNotFoundException.class);
        verify(products, never()).delete(any());
    }
}
