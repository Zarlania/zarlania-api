package com.zarlania.api.auth.controllers;

import com.zarlania.api.auth.dtos.RegisterRequest;
import com.zarlania.api.auth.dtos.ResendRequest;
import com.zarlania.api.auth.dtos.VerifyRequest;
import com.zarlania.api.auth.services.RegistrationService;
import com.zarlania.api.common.errors.ApiException;
import com.zarlania.api.common.errors.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

  private static final String INVALID_TOKEN_MESSAGE = "Invalid or expired verification token";

  private final RegistrationService registrationService;

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void register(@Valid @RequestBody RegisterRequest request) {
    registrationService.register(request.email(), request.username(), request.password());
  }

  @PostMapping("/verify")
  public void verify(@Valid @RequestBody VerifyRequest request) {
    if (!registrationService.verify(request.token())) {
      throw new ApiException(ErrorCode.INVALID_TOKEN, INVALID_TOKEN_MESSAGE);
    }
  }

  @PostMapping("/resend")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void resend(@Valid @RequestBody ResendRequest request) {
    registrationService.resend(request.email());
  }
}
