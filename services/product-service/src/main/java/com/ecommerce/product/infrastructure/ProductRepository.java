package com.ecommerce.product.infrastructure;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.ecommerce.product.domain.Product;
import com.ecommerce.product.domain.ProductView;

public interface ProductRepository extends JpaRepository<Product, Long> {

    // Every query is filtered by tenant (B3). The rating columns come from the B1 read model.
    String VIEW = """
            select p.id as id, p.name as name, p.description as description, p.price as price,
                   c.id as categoryId, c.name as categoryName,
                   (select avg(r.rating) from ProductRating r where r.productId = p.id) as averageRating,
                   (select count(r) from ProductRating r where r.productId = p.id) as ratingCount
            from Product p join p.category c
            where p.tenantId = :tenant""";

    @Query(VIEW + " and p.id = :id")
    Optional<ProductView> findViewById(Long id, String tenant);

    @Query(value = VIEW, countQuery = "select count(p) from Product p where p.tenantId = :tenant")
    Page<ProductView> findAllViews(String tenant, Pageable pageable);

    Optional<Product> findByIdAndTenantId(Long id, String tenantId);
}
