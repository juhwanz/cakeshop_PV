package com.cakeshop.global.infra.kakao;

import com.cakeshop.domain.notification.entity.NotificationDelivery;
import com.cakeshop.domain.notification.mapper.NotificationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 솔라피(Solapi) SMS 문자 전송 클라이언트
 */
@Component
public class SolapiKakaoAlimtalkClient {

    // 아무 회원 계정으로 로그인 하고 http://localhost:8080/api/notifications/test-sms
    // 테스트하면 콘솔에 결과 나오고, DB에 저장됨.

    private static final Logger log = LoggerFactory.getLogger(SolapiKakaoAlimtalkClient.class);
    private static final String SOLAPI_API_URL = "https://api.solapi.com/messages/v4/send"; // 솔라피 API 주소

    private final RestTemplate restTemplate; // HTTP 요청 전송용 객체

    @Value("${app.kakao.alimtalk.enabled:false}")
    private boolean enabled;

    @Value("${app.kakao.alimtalk.api-key:}")
    private String apiKey; // application.yml의 API Key

    @Value("${app.kakao.alimtalk.api-secret:}")
    private String apiSecret; // application.yml의 API Secret

    @Value("${app.kakao.alimtalk.sender-phone:01000000000}")
    private String senderPhone; // 발신자 핸드폰 번호

    public SolapiKakaoAlimtalkClient() {
        org.springframework.http.client.SimpleClientHttpRequestFactory requestFactory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(3000);
        requestFactory.setReadTimeout(3000);
        this.restTemplate = new RestTemplate(requestFactory);
    }

    @lombok.Getter
    @lombok.AllArgsConstructor
    public static class SmsResult {
        private final String status; // SENT, SKIPPED, FAILED
        private final String providerMessageId;
        private final String failureReason;
    }

    /**
     * 메시지 전송 메인 메서드
     */
    public SmsResult sendAlimtalk(Long notificationId, String recipientPhone, String title, String content) {
        // 수신자 전화번호가 없으면 경고 출력 후 종료
        if (recipientPhone == null || recipientPhone.trim().isEmpty()) {
            log.warn("문자 발송 실패: 수신자 전화번호가 없습니다. (NotificationId: {})", notificationId);
            return new SmsResult("FAILED", null, "No recipient phone number");
        }

        // 전화번호 하이픈(-) 제거 정제 (예: 010-1234-5678 -> 01012345678)
        String normalizedPhone = recipientPhone.replace("-", "").trim();
        String normalizedSender = senderPhone.replace("-", "").trim();

        // API Key 및 Secret이 설정되어 있으면 실제 전송, 없으면 가상 전송(Mock) 실행
        if (enabled && isConfigured()) {
            return sendRealSms(notificationId, normalizedPhone, normalizedSender, title, content);
        } else {
            return sendMockSms(notificationId, normalizedPhone, title, content);
        }
    }

    // API Key와 Secret 및 발신번호 설정 여부 확인 (더미 번호일 경우 mock 모드 전환)
    private boolean isConfigured() {
        return apiKey != null && !apiKey.trim().isEmpty() 
            && apiSecret != null && !apiSecret.trim().isEmpty()
            && senderPhone != null && !senderPhone.trim().isEmpty()
            && !"01000000000".equals(senderPhone.replace("-", "").trim());
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        if (phone.length() == 11) {
            return phone.substring(0, 3) + "-****-" + phone.substring(7);
        }
        return phone.substring(0, 3) + "-***-" + phone.substring(phone.length() - 4);
    }

    /**
     * [1] 솔라피 REST API 호출 - 실제 스마트폰으로 SMS/LMS 문자 전송
     */
    private SmsResult sendRealSms(Long notificationId, String recipient, String sender, String title, String content) {
        try {
            log.info("▶ [Solapi SMS API] 실제 문자 전송 시작 - 수신자: {}, 제목: {}", maskPhone(recipient), title);

            // 1. 요청 헤더 설정 (JSON 타입 및 HMAC 암호화 인증 헤더)
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", generateAuthorizationHeader());

            // 2. 솔라피 전송 데이터 조립 (간소화된 순수 문자 전송 구조)
            Map<String, Object> body = new HashMap<>();
            Map<String, Object> message = new HashMap<>();
            message.put("to", recipient);                 // 수신자 번호
            message.put("from", sender);                  // 발신자 번호
            message.put("text", "[" + title + "]\n" + content); // 발송할 문자 내용
            message.put("type", "LMS");                   // 장문 문자(LMS) 타입 고정
            if (notificationId != null) {
                message.put("customFields", Map.of("notificationId", String.valueOf(notificationId)));
            }

            body.put("message", message);

            // 3. 외부 솔라피 서버로 HTTP POST 요청 전송
            HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(SOLAPI_API_URL, requestEntity, Map.class);

            // 4. 발송 결과 메시지 ID 및 실패 목록 확인
            String providerMsgId = "SOLAPI_" + UUID.randomUUID().toString().substring(0, 8);
            Map responseBody = response.getBody();
            if (responseBody != null) {
                if (responseBody.containsKey("failedMessageList") && responseBody.get("failedMessageList") instanceof java.util.List list && !list.isEmpty()) {
                    log.error("✖ [Solapi SMS API] 일부 수신자 발송 실패 (failedMessageList)");
                    return new SmsResult("FAILED", null, "Solapi message delivery failed");
                }
                if (responseBody.containsKey("groupId")) {
                    providerMsgId = String.valueOf(responseBody.get("groupId"));
                }
            }

            log.info("✔ [Solapi SMS API] 성공적으로 전송되었습니다! MessageId: {}", providerMsgId);
            return new SmsResult("SENT", providerMsgId, null);

        } catch (Exception e) {
            log.error("✖ [Solapi SMS API] 전송 실패 (notificationId: {})", notificationId);
            return new SmsResult("FAILED", null, "Solapi API communication failure");
        }
    }

    /**
     * [2] 로컬/테스트 가상 발송 모드 (Mock Mode) - API 키가 없거나 비활성화 시 SKIPPED 상태로 리턴
     */
    private SmsResult sendMockSms(Long notificationId, String recipient, String title, String content) {
        String mockProviderMsgId = "MOCK_" + UUID.randomUUID().toString().substring(0, 8);
        
        log.info("================================================================================");
        log.info("[SMS 가상 발송 모드 (Mock Mode)] - 비활성화/테스트 모드로 인해 발송 스킵(SKIPPED)");
        log.info(" 수신자 전화번호 : {}", maskPhone(recipient));
        log.info(" 알림 제목       : {}", title);
        log.info(" 메시지 ID       : {}", mockProviderMsgId);
        log.info("================================================================================");

        return new SmsResult("SKIPPED", mockProviderMsgId, "Mock mode (Solapi disabled)");
    }

    /**
     * Solapi HMAC-SHA256 Authorization 암호화 인증 헤더 생성 (자바 표준 보안 문법)
     */
    private String generateAuthorizationHeader() {
        try {
            String date = ZonedDateTime.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            String salt = UUID.randomUUID().toString().replaceAll("-", "");
            String data = date + salt;

            Mac sha256HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256HMAC.init(secretKey);
            byte[] rawHmac = sha256HMAC.doFinal(data.getBytes(StandardCharsets.UTF_8));
            
            StringBuilder sb = new StringBuilder();
            for (byte b : rawHmac) {
                sb.append(String.format("%02x", b));
            }
            String signature = sb.toString();

            return "HMAC-SHA256 apiKey=" + apiKey + ", date=" + date + ", salt=" + salt + ", signature=" + signature;
        } catch (Exception e) {
            log.error("Solapi HMAC 인증 헤더 생성 실패: {}", e.getMessage());
            return "";
        }
    }
}
