package com.paysphere.product.controller;

import com.paysphere.common.exception.ResourceNotFoundException;
import com.paysphere.common.response.ApiResponse;
import com.paysphere.product.entity.Product;
import com.paysphere.product.repository.ProductRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ProductController — Exposes the product catalogue.
 *
 * Clients query this endpoint to display available items to purchase.
 * The price returned here is for display; during checkout the client sends
 * only the productCode and the backend reads the price directly from the database.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Tag(name = "Products", description = "Product catalogue and pricing")
public class ProductController {

    private final ProductRepository productRepository;

    @Operation(summary = "List all active products in the catalogue")
    @GetMapping
    public ResponseEntity<ApiResponse<List<Product>>> listActiveProducts() {
        List<Product> products = productRepository.findAll().stream()
                .filter(p -> Boolean.TRUE.equals(p.getActive()))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(products));
    }

    @Operation(summary = "Get a single product by code")
    @GetMapping("/{productCode}")
    public ResponseEntity<ApiResponse<Product>> getByProductCode(@PathVariable String productCode) {
        Product product = productRepository.findByProductCodeAndActiveTrue(productCode)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productCode));
        return ResponseEntity.ok(ApiResponse.success(product));
    }
}
