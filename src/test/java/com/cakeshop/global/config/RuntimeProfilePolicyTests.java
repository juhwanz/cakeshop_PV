package com.cakeshop.global.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RuntimeProfilePolicyTests {

    private static final String TEST_CLIENT_KEY = "test_ck_example";
    private static final String TEST_SECRET_KEY = "test_sk_example";

    @Test
    void validate_noProfile_rejectsImplicitExecution() {
        assertRejected(Set.of(), "실행 프로필이 없습니다");
    }

    @Test
    void validate_localOnly_allowsExternalFreeExecution() {
        assertThatCode(() -> validate(Set.of("local"))).doesNotThrowAnyException();
    }

    @Test
    void validate_localPreview_allowsPreviewExecution() {
        assertThatCode(() -> validate(Set.of("local", "preview")))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_localAndRds_rejectsAmbiguousDatabase() {
        assertRejected(Set.of("local", "rds"), "정확히 하나");
    }

    @Test
    void validate_previewWithoutLocal_rejectsCombination() {
        assertRejected(Set.of("rds", "preview"), "preview는 local");
    }

    @ParameterizedTest
    @ValueSource(strings = {"s3", "oauth", "smtp", "sms"})
    void validate_localWithExternalProfile_rejectsCombination(String externalProfile) {
        assertRejected(Set.of("local", externalProfile), "외부 연동 프로필");
    }

    @Test
    void validate_tossTestWithoutLocal_rejectsCombination() {
        assertRejected(Set.of("rds", "toss-test"), "local과 함께");
    }

    @Test
    void validate_previewWithTossTest_rejectsExternalCall() {
        assertThatThrownBy(() -> RuntimeProfilePolicy.validate(
                Set.of("local", "preview", "toss-test"),
                TEST_CLIENT_KEY,
                TEST_SECRET_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("preview에서는 Toss");
    }

    @Test
    void validate_tossTestWithMissingKey_rejectsConfiguration() {
        assertRejected(Set.of("local", "toss-test"), "모두 필요");
    }

    @Test
    void validate_tossTestWithLiveKeys_rejectsConfiguration() {
        assertThatThrownBy(() -> RuntimeProfilePolicy.validate(
                Set.of("local", "toss-test"),
                "live_ck_example",
                "live_sk_example"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("라이브 키는 거부");
    }

    @Test
    void validate_tossTestWithMismatchedTestKeyTypes_rejectsConfiguration() {
        assertThatThrownBy(() -> RuntimeProfilePolicy.validate(
                Set.of("local", "toss-test"),
                "test_ck_example",
                "test_gsk_example"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("같은 유형의 Toss 테스트 키 쌍");
    }

    @Test
    void validate_tossTestWithTestKeyPair_allowsExplicitCall() {
        assertThatCode(() -> RuntimeProfilePolicy.validate(
                Set.of("local", "toss-test"),
                TEST_CLIENT_KEY,
                TEST_SECRET_KEY))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_testProfile_allowsTestContext() {
        assertThatCode(() -> validate(Set.of("test"))).doesNotThrowAnyException();
    }

    @Test
    void validate_testWithExternalProfile_rejectsCombination() {
        assertRejected(Set.of("test", "s3"), "test는 실행·외부 연동 프로필과 조합할 수 없습니다");
    }

    private void assertRejected(Set<String> profiles, String message) {
        assertThatThrownBy(() -> validate(profiles))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(message);
    }

    private void validate(Set<String> profiles) {
        RuntimeProfilePolicy.validate(profiles, "", "");
    }
}
