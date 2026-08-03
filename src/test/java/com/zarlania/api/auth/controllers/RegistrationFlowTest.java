package com.zarlania.api.auth.controllers;

import static com.zarlania.api.testsupport.AuthEndpoints.verificationTokenIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zarlania.api.email.EmailMessage;
import com.zarlania.api.testsupport.FlowTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Registration from the first request to a working login, including what lands in the mailbox at
 * each step.
 *
 * <p>A flow test because the contract that matters spans requests and an email: the same 202
 * whether or not the address exists is enumeration-safe only if the <em>email</em> differs, and
 * that can be checked only by registering, reading what was sent, and following the link.
 *
 * <p>Runs against the real {@code AFTER_COMMIT} listener, so an email observed here is one a
 * committed registration actually caused.
 */
// Throttle limits are raised well above what this class sends from one client address: register
// defaults to 5/min and these methods together make more calls than that against one shared
// InMemoryRateLimiter. The throttle itself is covered against real limits by
// ClientIpThrottleEndToEndTest and AccountThrottleEndToEndTest.
@SpringBootTest(
    properties = {
      "zarlania.throttle.endpoints.register.limit=1000",
      "zarlania.throttle.endpoints.resend.limit=1000"
    })
class RegistrationFlowTest extends FlowTestBase {

  // This class asserts on total outbound volume, so it needs an empty recorder — safe here because
  // its property set is unique, which gives it a Spring context, and therefore a recorder, of its
  // own. A class sharing a context must scope its reads by recipient instead.
  @BeforeEach
  void clearRecordedEmails() {
    recordedEmails.clear();
  }

  @Test
  void registeringSendsOneVerificationEmailCarryingAUsableToken() throws Exception {
    auth.register("alice@example.com", "alice", PASSWORD).andExpect(status().isAccepted());

    assertThat(recordedEmails.messages()).hasSize(1);
    EmailMessage message = recordedEmails.messages().getFirst();
    assertThat(message.to()).isEqualTo("alice@example.com");
    assertThat(message.subject()).isEqualTo("Verify your Zarlania account");

    auth.verify(verificationTokenIn(message.textBody())).andExpect(status().isOk());
  }

  // Registering an address that is already verified must not say so in the response — that would be
  // an enumeration oracle. The existing owner is told instead, which is information only they can
  // read, and the notice carries no token because whoever triggered it has proved nothing.
  @Test
  void registeringAVerifiedAddressAgainNotifiesItsOwnerRatherThanTheCaller() throws Exception {
    registerAndVerify("bob@example.com", "bob");

    // A different username: registration checks username before address, so this has to stay free
    // to reach the address branch at all rather than failing as a taken username.
    auth.register("bob@example.com", "bobsecondattempt", PASSWORD).andExpect(status().isAccepted());

    // Scoped to the address rather than to the whole recorder, because the account's own
    // verification mail from setup is legitimately still there. Two messages, and the second one is
    // the notice — which is also the assertion that the notice went to the owner, not the caller.
    assertThat(recordedEmails.messagesTo("bob@example.com")).hasSize(2);
    assertThat(recordedEmails.messagesTo("bob@example.com").getLast().subject())
        .isEqualTo("Someone tried to register with your email");
  }

  // The realistic case the duplicate notice used to break: the first verification mail is missed,
  // so
  // the person registers again. They must get a working link, not "sign in with your existing
  // account instead" — advice that would strand them on a 403, since the account is unverified.
  @Test
  void reRegisteringAnUnverifiedAddressResendsVerificationAndLeavesTheOriginalPasswordInPlace()
      throws Exception {
    auth.register("nina@example.com", "nina", PASSWORD).andExpect(status().isAccepted());
    String firstToken = verificationTokenIn(lastEmailBody());
    recordedEmails.clear();

    auth.register("nina@example.com", "ninasecondattempt", "a-completely-different-password")
        .andExpect(status().isAccepted());

    assertThat(recordedEmails.messages()).hasSize(1);
    EmailMessage message = recordedEmails.messages().getFirst();
    assertThat(message.subject()).isEqualTo("Verify your Zarlania account");
    String secondToken = verificationTokenIn(message.textBody());
    assertThat(secondToken).isNotEqualTo(firstToken);

    // The link works, and the credentials the second attempt tried to set were never stored: a
    // caller who does not control the mailbox must not be able to overwrite someone's password.
    auth.verify(secondToken).andExpect(status().isOk());
    auth.login("nina", "a-completely-different-password").andExpect(status().isUnauthorized());
    auth.login("nina", PASSWORD).andExpect(status().isOk());
  }

  // A taken username is safe to report, unlike a taken address: usernames are public by design, and
  // a caller has to be told why the one they chose was refused.
  @Test
  void registeringWithATakenUsernameIsRefusedAndSendsNoEmail() throws Exception {
    auth.register("carol@example.com", "carolusername", PASSWORD).andExpect(status().isAccepted());
    recordedEmails.clear();

    auth.register("someoneelse@example.com", "carolusername", PASSWORD)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("auth.username-taken"));

    assertThat(recordedEmails.messages()).isEmpty();
  }

  @Test
  void resendIssuesAFreshTokenAndRetiresThePreviousOne() throws Exception {
    auth.register("erin@example.com", "erinusername", PASSWORD).andExpect(status().isAccepted());
    String firstToken = verificationTokenIn(lastEmailBody());
    recordedEmails.clear();

    auth.resend("erin@example.com").andExpect(status().isAccepted());

    assertThat(recordedEmails.messages()).hasSize(1);
    String secondToken = verificationTokenIn(lastEmailBody());
    assertThat(secondToken).isNotEqualTo(firstToken);

    // Issuing invalidates every outstanding token, so the first link is dead even though it never
    // expired — otherwise a leaked earlier email would stay redeemable indefinitely.
    auth.verify(firstToken).andExpect(status().isBadRequest());
    auth.verify(secondToken).andExpect(status().isOk());
  }

  // The same 202 and, crucially, the same silence as a real resend: the response cannot be used to
  // discover whether an address is registered.
  @Test
  void resendForAnUnknownAddressIsAcceptedAndSendsNothing() throws Exception {
    auth.resend("nobody@example.com").andExpect(status().isAccepted());

    assertThat(recordedEmails.messages()).isEmpty();
  }

  @Test
  void aVerificationTokenCanOnlyBeRedeemedOnce() throws Exception {
    auth.register("olly@example.com", "olly", PASSWORD).andExpect(status().isAccepted());
    String token = verificationTokenIn(lastEmailBody());

    auth.verify(token).andExpect(status().isOk());

    auth.verify(token)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("auth.invalid-token"));
  }
}
