package com.zarlania.api.organizations.repositories;

import com.zarlania.api.organizations.entities.OrganizationEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link OrganizationEntity}. Adds nothing to the inherited CRUD operations. */
public interface OrganizationRepository extends JpaRepository<OrganizationEntity, UUID> {}
