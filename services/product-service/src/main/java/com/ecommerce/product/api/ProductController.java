package com.ecommerce.product.api;

import java.net.URI;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ecommerce.product.application.PageResult;
import com.ecommerce.product.application.ProductCommandService;
import com.ecommerce.product.application.ProductDetails;
import com.ecommerce.product.application.ProductQueryService;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private static final String ADMIN_ONLY = "hasRole('ADMIN')";

    private final ProductQueryService queries;
    private final ProductCommandService commands;

    public ProductController(ProductQueryService queries, ProductCommandService commands) {
        this.queries = queries;
        this.commands = commands;
    }

    @GetMapping
    public PageResult<ProductDetails> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                           @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
                                           @RequestParam(defaultValue = "id,asc") String sort) {
        return queries.list(PageRequest.of(page, size, SortParameter.parse(sort)));
    }

    @GetMapping("/{id}")
    public ProductDetails get(@PathVariable long id) {
        return queries.getById(id);
    }

    @PostMapping
    @PreAuthorize(ADMIN_ONLY)
    public ResponseEntity<ProductDetails> create(@Valid @RequestBody ProductRequest request) {
        ProductDetails created = commands.create(request.toCommand());
        return ResponseEntity.created(URI.create("/api/v1/products/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    @PreAuthorize(ADMIN_ONLY)
    public ProductDetails update(@PathVariable long id, @Valid @RequestBody ProductRequest request) {
        return commands.update(id, request.toCommand());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(ADMIN_ONLY)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        commands.delete(id);
    }
}
