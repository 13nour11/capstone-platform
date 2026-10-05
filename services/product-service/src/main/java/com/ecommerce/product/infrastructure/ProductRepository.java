package com.ecommerce.product.infrastructure;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.ecommerce.product.domain.Product;
import com.ecommerce.product.domain.ProductView;

public interface ProductRepository extends JpaRepository<Product, Long> {

    String VIEW = """
            select p.id as id, p.name as name, p.description as description, p.price as price,
                   c.id as categoryId, c.name as categoryName
            from Product p join p.category c""";

    @Query(VIEW + " where p.id = :id")
    Optional<ProductView> findViewById(Long id);

    @Query(value = VIEW, countQuery = "select count(p) from Product p")
    Page<ProductView> findAllViews(Pageable pageable);
}
