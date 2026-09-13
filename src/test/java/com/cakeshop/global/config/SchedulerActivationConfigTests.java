package com.cakeshop.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

class SchedulerActivationConfigTests {

    @Test
    void previewProfile_doesNotRegisterSchedulingProcessor() {
        try (AnnotationConfigApplicationContext context = contextWithProfile("preview")) {
            assertThat(context.containsBean(
                    TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                    .isFalse();
        }
    }

    @Test
    void nonPreviewProfile_registersSchedulingProcessor() {
        try (AnnotationConfigApplicationContext context = contextWithProfile("local")) {
            assertThat(context.containsBean(
                    TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                    .isTrue();
        }
    }

    private AnnotationConfigApplicationContext contextWithProfile(String profile) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profile);
        context.register(SchedulerActivationConfig.class);
        context.refresh();
        return context;
    }
}
