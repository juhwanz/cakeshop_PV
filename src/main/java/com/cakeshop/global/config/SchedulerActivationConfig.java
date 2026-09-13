package com.cakeshop.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 공개 미리보기에서는 예약 작업을 등록하지 않아 데이터와 외부 연동을 결정적으로 유지한다. */
@Configuration
@EnableScheduling
@Profile("!preview")
public class SchedulerActivationConfig {
}
