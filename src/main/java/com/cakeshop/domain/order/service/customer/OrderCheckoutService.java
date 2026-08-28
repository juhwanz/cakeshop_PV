package com.cakeshop.domain.order.service.customer;

import com.cakeshop.domain.cart.dto.view.CartOrderItemView;
import com.cakeshop.domain.order.dto.view.customer.CartOrderCheckoutView;
import com.cakeshop.domain.order.dto.view.customer.GeneralOrderCheckoutView;
import com.cakeshop.domain.order.dto.view.customer.CustomOrderCheckoutView;
import com.cakeshop.domain.order.dto.view.customer.common.CheckoutOptionView;
import com.cakeshop.domain.order.dto.view.customer.common.PickupDateView;
import com.cakeshop.domain.order.dto.view.customer.common.PickupTimeView;
import com.cakeshop.domain.order.error.OrderErrorCode;
import com.cakeshop.domain.order.service.checkout.GeneralOrderItemPreparationService;
import com.cakeshop.domain.order.service.checkout.GeneralOrderItemPreparationService.PreparedGeneralOrderItem;
import com.cakeshop.domain.order.service.checkout.OrderAmountCalculator;
import com.cakeshop.domain.order.service.checkout.OrderOptionValidator;
import com.cakeshop.domain.order.service.checkout.OrderOptionValidator.ValidatedOption;
import com.cakeshop.domain.product.dto.view.ProductSalesInfo;
import com.cakeshop.domain.product.dto.view.ProductOptionGroupView;
import com.cakeshop.domain.product.entity.ProductType;
import com.cakeshop.domain.product.error.ProductErrorCode;
import com.cakeshop.domain.product.service.ProductQueryService;
import com.cakeshop.domain.product.service.ProductService;
import com.cakeshop.domain.store.dto.view.StoreView;
import com.cakeshop.domain.store.service.StoreService;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.error.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 일반 상품 주문서에 필요한 서버 기준 조회 데이터를 구성한다. */
@Service
@RequiredArgsConstructor
public class OrderCheckoutService {

    private static final int PICKUP_WINDOW_DAYS = 14;
    private static final long PAYMENT_EXPIRATION_MINUTES = 10L;
    private static final DateTimeFormatter DATE_LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN);
    private static final DateTimeFormatter TIME_LABEL_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm");

    private final ProductQueryService productQueryService;
    private final ProductService productService;
    private final GeneralOrderItemPreparationService generalOrderItemPreparationService;
    private final OrderOptionValidator orderOptionValidator;
    private final StoreService storeService;
    private final Clock clock;

    /** 클라이언트 가격을 사용하지 않고 현재 상품과 매장 정보로 주문서를 만든다. */
    @Transactional(readOnly = true)
    public GeneralOrderCheckoutView getGeneralCheckout(
            Long productId,
            Integer quantity,
            List<Long> optionIds
    ) {
        PreparedGeneralOrderItem prepared = generalOrderItemPreparationService.prepare(
                productId,
                quantity,
                optionIds
        );

        return new GeneralOrderCheckoutView(
                prepared.product().productId(),
                prepared.product().productName(),
                prepared.product().productType().getDisplayName(),
                prepared.quantity(),
                toCheckoutOptionViews(prepared.selectedOptions()),
                prepared.amounts().productAmount(),
                prepared.amounts().optionAmount(),
                prepared.amounts().totalAmount(),
                createPickupDates(storeService.getStoreView(), 0)
        );
    }

    /**
     * 장바구니에서 소유권 검증을 마친 여러 항목을 주문서 표시용으로 재검증·계산한다.
     * 최종 주문 저장 단계에서도 동일 항목을 다시 조회해 검증하므로 이 결과는 화면 표시 전용이다.
     */
    @Transactional(readOnly = true)
    public CartOrderCheckoutView getCartCheckout(List<CartOrderItemView> cartItems) {
        if (cartItems == null || cartItems.isEmpty()) {
            throw new BusinessException(OrderErrorCode.EMPTY_ORDER_ITEMS);
        }
        List<PreparedGeneralOrderItem> preparedItems = cartItems.stream()
                .map(item -> generalOrderItemPreparationService.prepare(
                        item.productId(),
                        item.quantity(),
                        item.optionIds()
                ))
                .toList();

        List<CartOrderCheckoutView.CartOrderItemView> items = preparedItems.stream()
                .map(prepared -> new CartOrderCheckoutView.CartOrderItemView(
                        prepared.product().productName(),
                        prepared.quantity(),
                        toCheckoutOptionViews(prepared.selectedOptions()),
                        prepared.amounts().totalAmount()
                ))
                .toList();
        BigDecimal productAmount = preparedItems.stream()
                .map(prepared -> prepared.amounts().productAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal optionAmount = preparedItems.stream()
                .map(prepared -> prepared.amounts().optionAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartOrderCheckoutView(
                items,
                productAmount,
                optionAmount,
                productAmount.add(optionAmount),
                createPickupDates(storeService.getStoreView(), 0)
        );
    }

    /** 수제 주문 요청 화면의 서버 기준 상품·옵션·금액·픽업 정보를 만든다. */
    @Transactional(readOnly = true)
    public CustomOrderCheckoutView getCustomCheckout(
            Long productId,
            List<Long> optionIds
    ) {
        if (productId == null || productId <= 0) {
            throw new BusinessException(OrderErrorCode.EMPTY_ORDER_ITEMS);
        }

        ProductSalesInfo product = productQueryService.getSalesInfo(productId);
        if (product.productType() != ProductType.CUSTOM) {
            throw new BusinessException(OrderErrorCode.CUSTOM_PRODUCT_REQUIRED);
        }
        if (!product.available()) {
            throw new BusinessException(ProductErrorCode.INSUFFICIENT_STOCK);
        }
        if (product.basePrice() == null || product.basePrice().signum() < 0
                || product.preparationDays() < 1) {
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR);
        }

        // 프론트에서 아무 옵션 ID나 보내도 해당 상품의 유효한 옵션인지 서버가 다시 확인한다.
        List<ValidatedOption> selectedOptions = orderOptionValidator.validate(productId, optionIds);
        OrderAmountCalculator.OrderAmounts amounts = OrderAmountCalculator.calculate(
                product.basePrice(),
                1,
                selectedOptions
        );

        // 총 계산 결과가 0 이하면 주문서 생성 X
        if (amounts.totalAmount().signum() <= 0) {
            throw new BusinessException(OrderErrorCode.INVALID_ORDER_AMOUNT);
        }

        return new CustomOrderCheckoutView(
                product.productId(),
                product.productName(),
                product.preparationDays(),
                toCheckoutOptionViews(selectedOptions),
                amounts.totalAmount(),
                createPickupDates(storeService.getStoreView(), product.preparationDays())
        );
    }

    /** 수제 옵션 선택 화면에 필요한 현재 판매 상품과 활성 옵션 그룹을 제공한다. */
    @Transactional(readOnly = true)
    public List<ProductOptionGroupView> getCustomOptionGroups(Long productId) {
        if (productId == null || productId <= 0) {
            throw new BusinessException(OrderErrorCode.EMPTY_ORDER_ITEMS);
        }
        ProductSalesInfo product = productQueryService.getSalesInfo(productId);
        if (product.productType() != ProductType.CUSTOM) {
            throw new BusinessException(OrderErrorCode.CUSTOM_PRODUCT_REQUIRED);
        }
        if (!product.available()) {
            throw new BusinessException(ProductErrorCode.INSUFFICIENT_STOCK);
        }
        return productService.getPublicOptionGroups(productId);
    }

    private List<CheckoutOptionView> toCheckoutOptionViews(List<ValidatedOption> options) {
        return options.stream()
                .map(option -> new CheckoutOptionView(
                        option.optionId(),
                        option.groupName(),
                        option.optionName(),
                        option.additionalPrice()
                ))
                .toList();
    }

    private List<PickupDateView> createPickupDates(StoreView store, int preparationDays) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime availableFrom = now.plusMinutes(PAYMENT_EXPIRATION_MINUTES)
                .plusDays(preparationDays);
        List<PickupDateView> pickupDates = new ArrayList<>();

        for (int dayOffset = 0; dayOffset < PICKUP_WINDOW_DAYS; dayOffset++) {
            LocalDate date = availableFrom.toLocalDate().plusDays(dayOffset);
            List<PickupTimeView> times = createPickupTimes(date, availableFrom, store);
            if (!times.isEmpty()) {
                pickupDates.add(new PickupDateView(
                        date,
                        date.format(DATE_LABEL_FORMATTER),
                        times
                ));
            }
        }

        return List.copyOf(pickupDates);
    }

    private List<PickupTimeView> createPickupTimes(
            LocalDate date,
            LocalDateTime availableFrom,
            StoreView store
    ) {
        if (isClosed(date, store)
                || store.pickupStartTime() == null
                || store.pickupEndTime() == null
                || store.pickupIntervalMinutes() == null
                || store.pickupIntervalMinutes() <= 0) {
            return List.of();
        }

        DayOfWeek dayOfWeek = date.getDayOfWeek();
        boolean weekend = dayOfWeek == DayOfWeek.SATURDAY
                || dayOfWeek == DayOfWeek.SUNDAY;
        LocalTime businessStart = weekend
                ? store.weekendOpenTime()
                : store.weekdayOpenTime();
        LocalTime businessEnd = weekend
                ? store.weekendCloseTime()
                : store.weekdayCloseTime();
        if (businessStart == null || businessEnd == null) {
            return List.of();
        }

        LocalDateTime cursor = date.atTime(store.pickupStartTime());
        LocalDateTime lastPickup = date.atTime(store.pickupEndTime());
        List<PickupTimeView> times = new ArrayList<>();

        while (!cursor.isAfter(lastPickup)) {
            LocalTime time = cursor.toLocalTime();
            if (cursor.isAfter(availableFrom)
                    && !time.isBefore(businessStart)
                    && !time.isAfter(businessEnd)) {
                times.add(new PickupTimeView(
                        cursor,
                        time.format(TIME_LABEL_FORMATTER)
                ));
            }
            cursor = cursor.plusMinutes(store.pickupIntervalMinutes());
        }

        return List.copyOf(times);
    }

    private boolean isClosed(LocalDate date, StoreView store) {
        return store.closedDays().contains(date.getDayOfWeek())
                || store.holidays().stream()
                        .anyMatch(holiday -> holiday.getHolidayDate().equals(date));
    }
}
