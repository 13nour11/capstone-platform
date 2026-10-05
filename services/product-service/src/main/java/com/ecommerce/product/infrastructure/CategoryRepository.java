package com.ecommerce.product.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ecommerce.product.domain.Category;

public interface CategoryRepository extends JpaRepository<Category, Long> {
}
