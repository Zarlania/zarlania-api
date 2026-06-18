package com.zarlania.api.users;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserDtoTest {

  @Test
  void fromCopiesIdAndEmail() {
    UUID expectedId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    User user = new User();
    user.setId(expectedId);
    user.setEmail("linus@example.com");

    UserDto dto = UserDto.from(user);

    assertThat(dto.id()).isEqualTo(expectedId);
    assertThat(dto.email()).isEqualTo("linus@example.com");
  }
}
