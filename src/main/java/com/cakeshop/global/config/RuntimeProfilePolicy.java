package com.cakeshop.global.config;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.StringUtils;

/** 애플리케이션 Bean 생성 전에 위험하거나 모호한 실행 프로필 조합을 거부한다. */
public final class RuntimeProfilePolicy
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Set<String> LOCAL_EXTERNAL_PROFILES =
            Set.of("s3", "oauth", "smtp", "sms");

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        ConfigurableEnvironment environment = applicationContext.getEnvironment();
        validate(
                Arrays.stream(environment.getActiveProfiles()).collect(Collectors.toSet()),
                environment.getProperty("app.payment.toss.client-key", ""),
                environment.getProperty("app.payment.toss.secret-key", "")
        );
    }

    static void validate(Set<String> profiles, String tossClientKey, String tossSecretKey) {
        if (profiles.contains("test")) {
            Set<String> unsafeTestProfiles = profiles.stream()
                    .filter(profile -> Set.of(
                            "local", "rds", "preview", "s3", "oauth", "smtp", "sms", "toss-test")
                            .contains(profile))
                    .collect(Collectors.toSet());
            if (!unsafeTestProfiles.isEmpty()) {
                throw invalid("test는 실행·외부 연동 프로필과 조합할 수 없습니다: "
                        + unsafeTestProfiles);
            }
            return;
        }
        if (profiles.isEmpty()) {
            throw invalid("실행 프로필이 없습니다. local 또는 rds를 명시하세요.");
        }

        boolean local = profiles.contains("local");
        boolean rds = profiles.contains("rds");
        if (local == rds) {
            throw invalid("데이터베이스 프로필은 local과 rds 중 정확히 하나만 선택해야 합니다.");
        }

        if (profiles.contains("preview") && !local) {
            throw invalid("preview는 local과 함께만 사용할 수 있습니다.");
        }
        if (local) {
            Set<String> forbidden = profiles.stream()
                    .filter(LOCAL_EXTERNAL_PROFILES::contains)
                    .collect(Collectors.toSet());
            if (!forbidden.isEmpty()) {
                throw invalid("local에서는 외부 연동 프로필을 사용할 수 없습니다: " + forbidden);
            }
        }

        if (profiles.contains("toss-test")) {
            if (!local) {
                throw invalid("toss-test는 로컬 DB를 사용하는 local과 함께만 사용할 수 있습니다.");
            }
            if (profiles.contains("preview")) {
                throw invalid("preview에서는 Toss 외부 호출을 활성화할 수 없습니다.");
            }
            validateTossTestKeys(tossClientKey, tossSecretKey);
        }
    }

    private static void validateTossTestKeys(String clientKey, String secretKey) {
        if (!StringUtils.hasText(clientKey) || !StringUtils.hasText(secretKey)) {
            throw invalid("toss-test에는 TOSS_CLIENT_KEY와 TOSS_SECRET_KEY가 모두 필요합니다.");
        }
        String normalizedClientKey = clientKey.trim();
        String normalizedSecretKey = secretKey.trim();
        boolean standardPair = normalizedClientKey.startsWith("test_ck_")
                && normalizedSecretKey.startsWith("test_sk_");
        boolean generatedPair = normalizedClientKey.startsWith("test_gck_")
                && normalizedSecretKey.startsWith("test_gsk_");
        if (!standardPair && !generatedPair) {
            throw invalid("toss-test에는 같은 유형의 Toss 테스트 키 쌍만 사용할 수 있습니다. "
                    + "라이브 키는 거부됩니다.");
        }
    }

    private static IllegalStateException invalid(String reason) {
        return new IllegalStateException("안전하지 않은 실행 프로필 설정입니다: " + reason);
    }
}
