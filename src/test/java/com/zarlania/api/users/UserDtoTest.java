package com.zarlania.api.users;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class UserDtoTest {

  @Test
  void fromCopiesIdAndEmail() {
    UUID expectedId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    User user = new User();
    // id has no setter (immutable, DB-generated); set it directly for this mapping unit test.
    ReflectionTestUtils.setField(user, "id", expectedId);
    user.setEmail("linus@example.com");

    UserDto dto = UserDto.from(user);

    assertThat(dto.id()).isEqualTo(expectedId);
    assertThat(dto.email()).isEqualTo("linus@example.com");
  }
}
