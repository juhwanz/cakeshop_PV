package com.cakeshop.domain.member.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cakeshop.domain.member.error.EmailVerificationSendException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DisabledEmailSenderTests {

    @Test
    void sendVerificationCode_smtpInactive_rejectsWithoutSending() {
        DisabledEmailSender sender = new DisabledEmailSender();

        assertThatThrownBy(() -> sender.sendVerificationCode(
                "member@example.com",
                "123456",
                Duration.ofMinutes(5)))
                .isInstanceOf(EmailVerificationSendException.class);
    }
}
