package com.paysphere.merchant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface MerchantRepository extends JpaRepository<Merchant, UUID> {

    boolean existsByName(String name);

    List<Merchant> findAllByOrderByCreatedAtDesc();
}