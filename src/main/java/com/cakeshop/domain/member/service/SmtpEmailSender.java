package com.cakeshop.domain.member.service;

import com.cakeshop.domain.member.error.EmailVerificationSendException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/** Spring Mail의 SMTP 연결로 회원 인증번호를 발송한다. */
@Service
@Profile("smtp")
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String fromName;

    public SmtpEmailSender(
            JavaMailSender mailSender,
            @Value("${app.member.mail.from-address}") String fromAddress,
            @Value("${app.member.mail.from-name}") String fromName) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.fromName = fromName;
    }

    @Override
    public void sendVerificationCode(String recipient, String code, Duration validFor) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message,
                    false,
                    StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(
                    fromAddress,
                    fromName,
                    StandardCharsets.UTF_8.name()));
            helper.setTo(recipient);
            helper.setSubject("[SweetHan] 이메일 인증번호 안내");
            helper.setText("""
                    SweetHan 이메일 인증번호입니다.

                    인증번호: %s

                    인증번호는 %d분 동안 유효합니다.
                    본인이 요청하지 않았다면 이 메일을 무시해 주세요.
                    """.formatted(code, validFor.toMinutes()));
            mailSender.send(message);
        } catch (MessagingException | UnsupportedEncodingException | MailException exception) {
            throw new EmailVerificationSendException();
        }
    }
}
