package com.zarlania.api.users;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UserDtoTest {

  @Test
  void fromCopiesIdAndEmail() {
    User user = new User();
    user.setEmail("linus@example.com");

    UserDto dto = UserDto.from(user);

    assertThat(dto.email()).isEqualTo("linus@example.com");
    assertThat(dto.id()).isEqualTo(user.getId());
  }
}
