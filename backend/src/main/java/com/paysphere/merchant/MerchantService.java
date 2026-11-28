package com.paysphere.merchant;

import com.paysphere.audit.service.AuditService;
import com.paysphere.common.exception.BusinessException;
import com.paysphere.merchant.MerchantDtos.MerchantResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MerchantService {

    private final MerchantRepository merchantRepository;
    private final AuditService auditService;

    /** Platform admin creates a merchant. The audit entry is written in the same transaction. */
    @Transactional
    public MerchantResponse createMerchant(String rawName) {
        String name = rawName.trim();
        if (merchantRepository.existsByName(name)) {
            throw new BusinessException("MERCHANT_EXISTS", "A merchant with this name already exists");
        }
        Merchant merchant = merchantRepository.saveAndFlush(Merchant.builder().name(name).build());
        auditService.record("MERCHANT_CREATED", "Merchant", merchant.getId(),
                "Merchant created: " + name, "SYSTEM");
        return toResponse(merchant);
    }

    @Transactional(readOnly = true)
    public List<MerchantResponse> listMerchants() {
        return merchantRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    private MerchantResponse toResponse(Merchant merchant) {
        return new MerchantResponse(merchant.getId(), merchant.getName(),
                merchant.getStatus(), merchant.getCreatedAt());
    }
}