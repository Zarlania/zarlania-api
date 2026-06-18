package com.zarlania.api.persistence;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/** Enables Spring Data JPA auditing so {@link Auditable} timestamps are populated. */
@Configuration
@EnableJpaAuditing
public class JpaConfig {}
