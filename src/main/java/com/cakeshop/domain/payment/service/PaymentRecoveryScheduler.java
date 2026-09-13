package com.cakeshop.domain.payment.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 서버 재시작이나 일시 장애 뒤 남은 보상·환불 취소를 주기적으로 재처리한다. */
@Component
@Profile("toss-test & !preview")
public class PaymentRecoveryScheduler {

    private final PaymentCompensationProcessor compensationProcessor;
    private final RefundFacade refundFacade;
    private final int batchSize;

    public PaymentRecoveryScheduler(
            PaymentCompensationProcessor compensationProcessor,
            RefundFacade refundFacade,
            @Value("${app.payment.recovery.batch-size:50}") int batchSize
    ) {
        this.compensationProcessor = compensationProcessor;
        this.refundFacade = refundFacade;
        this.batchSize = batchSize;
    }

    @Scheduled(
            fixedDelayString = "${app.payment.recovery.fixed-delay:60s}",
            initialDelayString = "${app.payment.recovery.initial-delay:60s}"
    )
    public void recoverPendingCompensations() {
        compensationProcessor.recoverPendingCompensations(batchSize);
        refundFacade.recoverPendingCancellations(batchSize);
    }
}
