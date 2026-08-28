package com.cakeshop.domain.order.service.customer;

import com.cakeshop.domain.cart.dto.view.CartOrderItemView;
import com.cakeshop.domain.order.dto.view.customer.CartOrderCheckoutView;
import com.cakeshop.domain.order.dto.view.customer.GeneralOrderCheckoutView;
import com.cakeshop.domain.order.dto.view.customer.common.PickupTimeView;
import com.cakeshop.domain.order.error.OrderErrorCode;
import com.cakeshop.domain.order.service.checkout.GeneralOrderItemPreparationService;
import com.cakeshop.domain.order.service.checkout.OrderOptionValidator;
import com.cakeshop.domain.order.service.customer.OrderCheckoutService;
import com.cakeshop.domain.order.service.checkout.OrderOptionValidator.ValidatedOption;
import com.cakeshop.domain.product.dto.view.ProductSalesInfo;
import com.cakeshop.domain.product.entity.ProductType;
import com.cakeshop.domain.product.service.ProductQueryService;
import com.cakeshop.domain.product.service.ProductService;
import com.cakeshop.domain.store.dto.view.StoreView;
import com.cakeshop.domain.store.entity.StoreHoliday;
import com.cakeshop.domain.store.service.StoreService;
import com.cakeshop.global.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderCheckoutServiceTests {

    private static final ZoneId TEST_ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime FIXED_NOW =
            LocalDateTime.of(2026, 8, 3, 10, 15);
    private static final Clock FIXED_CLOCK = Clock.fixed(
            FIXED_NOW.atZone(TEST_ZONE).toInstant(),
            TEST_ZONE
    );

    @Mock
    private ProductQueryService productQueryService;

    @Mock
    private ProductService productService;

    @Mock
    private OrderOptionValidator orderOptionValidator;

    @Mock
    private StoreService storeService;

    private OrderCheckoutService orderCheckoutService;

    @BeforeEach
    void setUp() {
        orderCheckoutService = new OrderCheckoutService(
                productQueryService,
                productService,
                new GeneralOrderItemPreparationService(
                        productQueryService,
                        orderOptionValidator
                ),
                orderOptionValidator,
                storeService,
                FIXED_CLOCK
        );
    }

    @Test
    void getGeneralCheckout_validSelection_calculatesAmountsAndPickupSlots() {
        when(productQueryService.getSalesInfo(1L)).thenReturn(
                new ProductSalesInfo(
                        1L,
                        "딸기 생크림 케이크",
                        ProductType.GENERAL,
                        0,
                        true,
                        BigDecimal.valueOf(35_000),
                        10
                )
        );
        when(orderOptionValidator.validate(1L, List.of(101L))).thenReturn(
                List.of(new ValidatedOption(
                        101L,
                        "케이크 크기",
                        "2호",
                        BigDecimal.valueOf(10_000)
                ))
        );
        when(storeService.getStoreView()).thenReturn(storeView());

        GeneralOrderCheckoutView checkout =
                orderCheckoutService.getGeneralCheckout(
                        1L,
                        2,
                        List.of(101L)
                );

        assertThat(checkout.productName()).isEqualTo("딸기 생크림 케이크");
        assertThat(checkout.productAmount()).isEqualByComparingTo("70000");
        assertThat(checkout.optionAmount()).isEqualByComparingTo("20000");
        assertThat(checkout.totalAmount()).isEqualByComparingTo("90000");
        assertThat(checkout.selectedOptions()).singleElement().satisfies(option -> {
            assertThat(option.id()).isEqualTo(101L);
            assertThat(option.name()).isEqualTo("2호");
        });
        assertThat(checkout.pickupDates().getFirst().date())
                .isEqualTo(LocalDate.of(2026, 8, 3));
        assertThat(checkout.pickupDates().getFirst().times().getFirst().value())
                .isEqualTo(LocalDateTime.of(2026, 8, 3, 10, 30));
        assertThat(checkout.pickupDates())
                .noneMatch(date -> date.date().getDayOfWeek() == DayOfWeek.TUESDAY)
                .noneMatch(date -> date.date().equals(LocalDate.of(2026, 8, 5)));
    }

    @Test
    void getGeneralCheckout_unlimitedStockQuantityOverLimit_rejectsCheckout() {
        when(productQueryService.getSalesInfo(1L)).thenReturn(
                new ProductSalesInfo(
                        1L,
                        "딸기 생크림 케이크",
                        ProductType.GENERAL,
                        0,
                        true,
                        BigDecimal.valueOf(35_000),
                        null
                )
        );

        assertThatThrownBy(() -> orderCheckoutService.getGeneralCheckout(
                1L,
                11,
                List.of()
        )).isInstanceOfSatisfying(
                BusinessException.class,
                error -> assertThat(error.getErrorCode())
                        .isEqualTo(OrderErrorCode.INVALID_QUANTITY)
        );
    }

    @Test
    void getGeneralCheckout_zeroTotalAmount_rejectsCheckout() {
        when(productQueryService.getSalesInfo(1L)).thenReturn(
                new ProductSalesInfo(
                        1L,
                        "무료 케이크",
                        ProductType.GENERAL,
                        0,
                        true,
                        BigDecimal.ZERO,
                        10
                )
        );
        when(orderOptionValidator.validate(1L, List.of())).thenReturn(List.of());

        assertThatThrownBy(() -> orderCheckoutService.getGeneralCheckout(
                1L,
                1,
                List.of()
        )).isInstanceOfSatisfying(
                BusinessException.class,
                error -> assertThat(error.getErrorCode())
                        .isEqualTo(OrderErrorCode.INVALID_ORDER_AMOUNT)
        );
    }

    @Test
    void getCartCheckout_multipleItems_loadsStoreOnce() {
        when(productQueryService.getSalesInfo(1L)).thenReturn(
                new ProductSalesInfo(
                        1L,
                        "딸기 생크림 케이크",
                        ProductType.GENERAL,
                        0,
                        true,
                        BigDecimal.valueOf(35_000),
                        10
                )
        );
        when(productQueryService.getSalesInfo(2L)).thenReturn(
                new ProductSalesInfo(
                        2L,
                        "초코 생크림 케이크",
                        ProductType.GENERAL,
                        0,
                        true,
                        BigDecimal.valueOf(40_000),
                        10
                )
        );
        when(orderOptionValidator.validate(1L, List.of())).thenReturn(List.of());
        when(orderOptionValidator.validate(2L, List.of())).thenReturn(List.of());
        when(storeService.getStoreView()).thenReturn(storeView());

        CartOrderCheckoutView checkout = orderCheckoutService.getCartCheckout(List.of(
                new CartOrderItemView(11L, 1L, 1, null, List.of()),
                new CartOrderItemView(12L, 2L, 2, null, List.of())
        ));

        assertThat(checkout.totalAmount()).isEqualByComparingTo("115000");
        assertThat(checkout.items()).hasSize(2);
        verify(storeService, times(1)).getStoreView();
    }

    @Test
    void getCustomCheckout_onlyOffersPickupSlotsAfterPreparationPeriod() {
        when(productQueryService.getSalesInfo(6L)).thenReturn(
                new ProductSalesInfo(
                        6L,
                        "레터링 케이크",
                        ProductType.CUSTOM,
                        2,
                        true,
                        BigDecimal.valueOf(55_000),
                        null
                )
        );
        when(orderOptionValidator.validate(6L, List.of())).thenReturn(List.of());
        when(storeService.getStoreView()).thenReturn(storeView());

        var checkout = orderCheckoutService.getCustomCheckout(6L, List.of());

        assertThat(checkout.pickupDates().getFirst().date())
                .isEqualTo(LocalDate.of(2026, 8, 6));
        assertThat(checkout.pickupDates().stream()
                .flatMap(date -> date.times().stream())
                .map(PickupTimeView::value))
                .allMatch(value -> value.isAfter(LocalDateTime.of(2026, 8, 5, 10, 25)));
    }

    @Test
    void getCustomCheckout_longPreparationPeriod_offersPickupSlotsAfterPreparationWindow() {
        when(productQueryService.getSalesInfo(6L)).thenReturn(
                new ProductSalesInfo(
                        6L,
                        "레터링 케이크",
                        ProductType.CUSTOM,
                        14,
                        true,
                        BigDecimal.valueOf(55_000),
                        null
                )
        );
        when(orderOptionValidator.validate(6L, List.of())).thenReturn(List.of());
        when(storeService.getStoreView()).thenReturn(storeView());

        var checkout = orderCheckoutService.getCustomCheckout(6L, List.of());

        assertThat(checkout.pickupDates().getFirst().date())
                .isEqualTo(LocalDate.of(2026, 8, 17));
        assertThat(checkout.pickupDates().getFirst().times().getFirst().value())
                .isEqualTo(LocalDateTime.of(2026, 8, 17, 10, 30));
    }

    private StoreView storeView() {
        StoreHoliday holiday = new StoreHoliday();
        holiday.setHolidayDate(LocalDate.of(2026, 8, 5));

        return new StoreView(
                1L,
                "케이크샵",
                null,
                null,
                "서울시",
                "02-000-0000",
                LocalTime.of(9, 0),
                LocalTime.of(18, 0),
                LocalTime.of(10, 0),
                LocalTime.of(16, 0),
                Set.of(DayOfWeek.TUESDAY),
                "1층 픽업 데스크",
                LocalTime.of(10, 0),
                LocalTime.of(12, 0),
                30,
                List.of(holiday)
        );
    }
}
