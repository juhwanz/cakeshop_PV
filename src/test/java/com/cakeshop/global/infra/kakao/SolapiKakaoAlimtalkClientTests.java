package com.cakeshop.global.infra.kakao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SolapiKakaoAlimtalkClientTests {

    @Test
    void sendAlimtalk_profileDisabled_skipsEvenWithCredentials() {
        SolapiKakaoAlimtalkClient client = new SolapiKakaoAlimtalkClient();
        ReflectionTestUtils.setField(client, "enabled", false);
        ReflectionTestUtils.setField(client, "apiKey", "configured-api-key");
        ReflectionTestUtils.setField(client, "apiSecret", "configured-api-secret");
        ReflectionTestUtils.setField(client, "senderPhone", "01012345678");

        SolapiKakaoAlimtalkClient.SmsResult result = client.sendAlimtalk(
                1L,
                "01098765432",
                "테스트 알림",
                "외부로 전송되면 안 됩니다.");

        assertThat(result.getStatus()).isEqualTo("SKIPPED");
        assertThat(result.getFailureReason()).isEqualTo("Mock mode (Solapi disabled)");
    }
}
