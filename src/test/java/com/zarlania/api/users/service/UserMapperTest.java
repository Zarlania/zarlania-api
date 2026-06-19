package com.zarlania.api.users.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.zarlania.api.users.dto.User;
import com.zarlania.api.users.entity.UserEntity;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class UserMapperTest {

  @Test
  void toDtoCopiesIdAndEmail() {
    UUID expectedId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UserEntity entity = new UserEntity();
    // id has no setter (immutable, DB-generated); set it directly for this mapping unit test.
    ReflectionTestUtils.setField(entity, "id", expectedId);
    entity.setEmail("linus@example.com");
    entity.setDisplayName("Linus");

    User dto = new UserMapper().toDto(entity);

    assertThat(dto.id()).isEqualTo(expectedId);
    assertThat(dto.email()).isEqualTo("linus@example.com");
    assertThat(dto.displayName()).isEqualTo("Linus");
  }
}
