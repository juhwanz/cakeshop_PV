package com.cakeshop.domain.member.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mail.javamail.JavaMailSender;

class EmailSenderProfileTests {

    @Test
    void localProfile_registersDisabledSenderOnly() {
        try (AnnotationConfigApplicationContext context = contextWithProfile("local")) {
            assertThat(context.getBean(EmailSender.class))
                    .isInstanceOf(DisabledEmailSender.class);
            assertThat(context.getBeansOfType(SmtpEmailSender.class)).isEmpty();
        }
    }

    @Test
    void smtpProfile_registersSmtpSenderOnly() {
        try (AnnotationConfigApplicationContext context = contextWithProfile("smtp")) {
            assertThat(context.getBean(EmailSender.class))
                    .isInstanceOf(SmtpEmailSender.class);
            assertThat(context.getBeansOfType(DisabledEmailSender.class)).isEmpty();
        }
    }

    private AnnotationConfigApplicationContext contextWithProfile(String profile) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profile);
        context.getEnvironment().getPropertySources().addFirst(
                new org.springframework.core.env.MapPropertySource(
                        "mail-test",
                        java.util.Map.of(
                                "app.member.mail.from-address", "sender@example.com",
                                "app.member.mail.from-name", "SweetHan")));
        context.registerBean(JavaMailSender.class, () -> mock(JavaMailSender.class));
        context.register(DisabledEmailSender.class, SmtpEmailSender.class);
        context.refresh();
        return context;
    }
}
