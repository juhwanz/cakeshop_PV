package com.cakeshop.domain.member.service;

import com.cakeshop.domain.member.error.EmailVerificationSendException;
import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** SMTP 프로필이 없을 때 외부 메일 발송을 명시적으로 거부한다. */
@Service
@Profile("!smtp")
public class DisabledEmailSender implements EmailSender {

    @Override
    public void sendVerificationCode(String recipient, String code, Duration validFor) {
        throw new EmailVerificationSendException();
    }
}
