package com.ims.partner.infrastructure.persistence;

import com.ims.partner.domain.model.*;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ContactRepository extends JpaRepository<Contact, UUID> {

    List<Contact> findByCompanyIdOrderByPrimaryContactDescNameAsc(UUID companyId);
}
