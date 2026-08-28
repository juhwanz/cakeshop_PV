package com.cakeshop.domain.order.service.checkout;

import com.cakeshop.domain.order.error.OrderErrorCode;
import com.cakeshop.domain.order.service.checkout.OrderAmountCalculator.OrderAmounts;
import com.cakeshop.domain.order.service.checkout.OrderOptionValidator.ValidatedOption;
import com.cakeshop.domain.product.dto.view.ProductSalesInfo;
import com.cakeshop.domain.product.entity.ProductType;
import com.cakeshop.domain.product.error.ProductErrorCode;
import com.cakeshop.domain.product.service.ProductQueryService;
import com.cakeshop.global.error.BusinessException;
import com.cakeshop.global.error.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 일반 상품 주문서 조회와 주문 생성이 공유하는 상품·수량·옵션·금액 검증을 담당한다. */
@Service
@RequiredArgsConstructor
public class GeneralOrderItemPreparationService {

    private static final int MAX_UNLIMITED_STOCK_QUANTITY = 10;

    private final ProductQueryService productQueryService;
    private final OrderOptionValidator orderOptionValidator;

    public PreparedGeneralOrderItem prepare(
            Long productId,
            Integer quantity,
            List<Long> optionIds
    ) {
        if (productId == null || productId <= 0) {
            throw new BusinessException(OrderErrorCode.EMPTY_ORDER_ITEMS);
        }
        if (quantity == null || quantity <= 0) {
            throw new BusinessException(OrderErrorCode.INVALID_QUANTITY);
        }

        ProductSalesInfo product = productQueryService.getSalesInfo(productId);
        validateProduct(product, quantity);
        List<ValidatedOption> selectedOptions = orderOptionValidator.validate(productId, optionIds);
        OrderAmounts amounts = OrderAmountCalculator.calculate(
                product.basePrice(),
                quantity,
                selectedOptions
        );
        if (amounts.totalAmount().signum() <= 0) {
            throw new BusinessException(OrderErrorCode.INVALID_ORDER_AMOUNT);
        }

        return new PreparedGeneralOrderItem(product, quantity, selectedOptions, amounts);
    }

    private void validateProduct(ProductSalesInfo product, int quantity) {
        if (product.productType() != ProductType.GENERAL) {
            throw new BusinessException(OrderErrorCode.GENERAL_PRODUCT_REQUIRED);
        }
        if (product.stockQuantity() == null && quantity > MAX_UNLIMITED_STOCK_QUANTITY) {
            throw new BusinessException(OrderErrorCode.INVALID_QUANTITY);
        }
        if (product.basePrice() == null || product.basePrice().signum() < 0) {
            throw new BusinessException(CommonErrorCode.INTERNAL_ERROR);
        }
        if (!product.available()
                || product.stockQuantity() != null && product.stockQuantity() < quantity) {
            throw new BusinessException(ProductErrorCode.INSUFFICIENT_STOCK);
        }
    }

    public record PreparedGeneralOrderItem(
            ProductSalesInfo product,
            int quantity,
            List<ValidatedOption> selectedOptions,
            OrderAmounts amounts
    ) {
    }
}
