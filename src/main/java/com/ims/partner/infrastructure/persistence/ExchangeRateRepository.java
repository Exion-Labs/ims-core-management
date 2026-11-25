package com.ims.partner.infrastructure.persistence;

import com.ims.partner.domain.model.*;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, UUID> {

    Optional<ExchangeRate> findByBaseCurrencyAndTargetCurrency(String baseCurrency, String targetCurrency);

    List<ExchangeRate> findAllByOrderByTargetCurrencyAsc();
}
