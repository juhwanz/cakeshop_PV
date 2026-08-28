package com.cakeshop.domain.order.service;

import com.cakeshop.domain.coupon.service.CouponOrderCommandService;
import com.cakeshop.domain.cart.service.CartOrderQueryService;
import com.cakeshop.domain.member.service.MemberService;
import com.cakeshop.domain.member.service.MemberCouponQueryService;
import com.cakeshop.domain.order.dto.form.customer.OrderGeneralCreateForm;
import com.cakeshop.domain.order.service.checkout.GeneralOrderItemPreparationService;
import com.cakeshop.domain.order.service.checkout.OrderOptionValidator;
import com.cakeshop.domain.order.service.checkout.PickupAvailabilityPolicy;
import com.cakeshop.domain.payment.error.PaymentErrorCode;
import com.cakeshop.domain.payment.service.PaymentOrderPreparationCommandService;
import com.cakeshop.domain.product.dto.view.ProductOptionGroupView;
import com.cakeshop.domain.product.dto.view.ProductOptionItemView;
import com.cakeshop.domain.product.dto.view.ProductSalesInfo;
import com.cakeshop.domain.product.entity.ProductType;
import com.cakeshop.domain.product.service.ProductQueryService;
import com.cakeshop.domain.product.service.ProductService;
import com.cakeshop.domain.store.dto.view.StoreView;
import com.cakeshop.domain.store.service.StoreService;
import com.cakeshop.global.config.MariaDbIntegrationTest;
import com.cakeshop.global.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@MybatisTest
@Import({
        OrderService.class,
        GeneralOrderItemPreparationService.class,
        PickupAvailabilityPolicy.class,
        OrderOptionValidator.class
})
@MariaDbIntegrationTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderServiceRollbackIntegrationTests {

    @MockitoBean
    private CouponOrderCommandService couponOrderCommandService;

    @MockitoBean
    private CartOrderQueryService cartOrderQueryService;

    private static final ZoneId TEST_ZONE = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime FIXED_NOW =
            LocalDateTime.of(2026, 7, 31, 10, 0);

    private final OrderService orderService;
    private final JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ProductQueryService productQueryService;

    @MockitoBean
    private ProductService productService;

    @MockitoBean
    private StoreService storeService;

    @MockitoBean
    private PaymentOrderPreparationCommandService paymentPreparationService;

    @MockitoBean
    private Clock clock;

    @MockitoBean
    private MemberService memberService;

    @MockitoBean
    private MemberCouponQueryService memberCouponQueryService;

    private String suffix;
    private long memberId;
    private long productId;
    private long productOptionId;

    @Autowired
    OrderServiceRollbackIntegrationTests(
            OrderService orderService,
            JdbcTemplate jdbcTemplate
    ) {
        this.orderService = orderService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @BeforeEach
    void setUp() {
        suffix = Long.toString(System.nanoTime());
        memberId = insertMember();
        when(memberService.isActiveMember(memberId)).thenReturn(true);
        when(memberCouponQueryService.lockActiveCouponIssuableMember(memberId)).thenReturn(true);
        productId = insertProduct();
        productOptionId = insertProductOption();

        when(storeService.getStoreView()).thenReturn(storeView());
        when(clock.instant())
                .thenReturn(FIXED_NOW.atZone(TEST_ZONE).toInstant());
        when(clock.getZone()).thenReturn(TEST_ZONE);
        when(productQueryService.getSalesInfo(productId))
                .thenReturn(new ProductSalesInfo(
                        productId,
                        "롤백 테스트 상품 " + suffix,
                        ProductType.GENERAL,
                        0,
                        true,
                        BigDecimal.valueOf(30_000),
                        10
                ));
        when(productService.getPublicOptionGroups(productId))
                .thenReturn(List.of(new ProductOptionGroupView(
                        1L,
                        "크기",
                        true,
                        "SINGLE",
                        List.of(new ProductOptionItemView(
                                productOptionId,
                                "2호",
                                BigDecimal.valueOf(5_000)
                        ))
                )));
        doThrow(new BusinessException(PaymentErrorCode.PAYMENT_PREPARATION_FAILED))
                .when(paymentPreparationService)
                .prepareReadyPayment(anyLong(), anyString(), any(BigDecimal.class));
    }

    @Test
    void createGeneralOrder_paymentSaveFails_rollsBackAllOrderData() {
        assertThatThrownBy(() ->
                orderService.createGeneralOrder(memberId, createForm())
        ).isInstanceOfSatisfying(
                BusinessException.class,
                error -> assertThat(error.getErrorCode())
                        .isEqualTo(PaymentErrorCode.PAYMENT_PREPARATION_FAILED)
        );

        assertThat(countOrders()).isZero();
        assertThat(countOrderItems()).isZero();
        assertThat(countOrderItemOptions()).isZero();
    }

    private OrderGeneralCreateForm createForm() {
        OrderGeneralCreateForm form = new OrderGeneralCreateForm();
        form.setRequestKey(UUID.randomUUID().toString());
        form.setOrdererName("주문자");
        form.setOrdererPhone("010-1111-2222");
        form.setPickupName("수령자");
        form.setPickupPhone("010-3333-4444");
        form.setPickupAt(FIXED_NOW.plusDays(3));
        form.setRequestMessage("롤백 테스트");
        form.setProductId(productId);
        form.setQuantity(2);
        form.setOptionIds(List.of(productOptionId));
        return form;
    }

    private StoreView storeView() {
        return new StoreView(
                1L,
                "테스트 매장",
                null,
                null,
                "서울시",
                "02-0000-0000",
                LocalTime.of(9, 0),
                LocalTime.of(20, 0),
                LocalTime.of(9, 0),
                LocalTime.of(20, 0),
                Set.<DayOfWeek>of(),
                "1층",
                LocalTime.of(10, 0),
                LocalTime.of(19, 0),
                60,
                List.of()
        );
    }

    private long countOrders() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE member_id = ?",
                Long.class,
                memberId
        );
    }

    private long countOrderItems() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_items WHERE product_id = ?",
                Long.class,
                productId
        );
    }

    private long countOrderItemOptions() {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM order_item_options
                WHERE product_option_id = ?
                """,
                Long.class,
                productOptionId
        );
    }

    private long insertMember() {
        String email = "order-rollback-" + suffix + "@example.com";
        jdbcTemplate.update(
                """
                INSERT INTO members (
                    email,
                    password,
                    name,
                    nickname,
                    phone,
                    role,
                    status
                )
                VALUES (?, NULL, ?, ?, ?, 'USER', 'ACTIVE')
                """,
                email,
                "롤백 테스트 회원",
                "롤백테스트",
                "010-0000-0000"
        );

        return jdbcTemplate.queryForObject(
                "SELECT id FROM members WHERE email = ?",
                Long.class,
                email
        );
    }

    private long insertProduct() {
        String categoryCode = "ORDER_ROLLBACK_" + suffix;
        jdbcTemplate.update(
                """
                INSERT INTO categories (
                    code,
                    name,
                    sort_order,
                    is_active
                )
                VALUES (?, ?, 999, 1)
                """,
                categoryCode,
                "주문 롤백 테스트"
        );
        long categoryId = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE code = ?",
                Long.class,
                categoryCode
        );

        String productName = "롤백 테스트 상품 " + suffix;
        jdbcTemplate.update(
                """
                INSERT INTO products (
                    category_id,
                    name,
                    description,
                    base_price,
                    stock_quantity,
                    product_type,
                    preparation_days,
                    status
                )
                VALUES (?, ?, '', 30000, 10, 'GENERAL', 0, 'ACTIVE')
                """,
                categoryId,
                productName
        );

        return jdbcTemplate.queryForObject(
                "SELECT id FROM products WHERE name = ?",
                Long.class,
                productName
        );
    }

    private long insertProductOption() {
        jdbcTemplate.update(
                """
                INSERT INTO product_option_groups (
                    product_id,
                    name,
                    required,
                    selection_type,
                    sort_order
                )
                VALUES (?, '크기', 1, 'SINGLE', 1)
                """,
                productId
        );
        long groupId = jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM product_option_groups
                WHERE product_id = ?
                  AND name = '크기'
                """,
                Long.class,
                productId
        );

        jdbcTemplate.update(
                """
                INSERT INTO product_options (
                    option_group_id,
                    name,
                    additional_price,
                    status,
                    sort_order
                )
                VALUES (?, '2호', 5000, 'ACTIVE', 1)
                """,
                groupId
        );

        return jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM product_options
                WHERE option_group_id = ?
                  AND name = '2호'
                """,
                Long.class,
                groupId
        );
    }
}
