package com.zarlania.api.organizations.repositories;

import com.zarlania.api.organizations.entities.Organization;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationRepository extends JpaRepository<Organization, UUID> {}
