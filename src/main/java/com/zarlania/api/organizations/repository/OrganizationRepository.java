package com.zarlania.api.organizations.repository;

import com.zarlania.api.organizations.entity.OrganizationEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence access for {@link OrganizationEntity}. Internal to the {@code organizations} domain.
 */
public interface OrganizationRepository extends JpaRepository<OrganizationEntity, UUID> {}
