package site.pplee.jcode.ai.message;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostEstimateTest {

    @Test
    void ofComputesDecimal128Total() {
        var estimate = CostEstimate.of(
                "USD",
                new BigDecimal("0.1"),
                new BigDecimal("0.2"),
                new BigDecimal("0.3"),
                new BigDecimal("0.4"));
        assertEquals(0, estimate.total().compareTo(new BigDecimal("1.0")));
        assertEquals(CostEstimate.sum(
                estimate.input(), estimate.output(), estimate.cacheRead(), estimate.cacheWrite()),
                estimate.total());
        assertTrue(estimate.toString().contains("estimate=true"));
        assertTrue(estimate.toString().contains("USD"));
    }

    @Test
    void rejectsNullOrNegativeAmountsAndMismatchedTotal() {
        assertThrows(NullPointerException.class,
                () -> new CostEstimate("USD", null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> CostEstimate.of(
                "USD", new BigDecimal("-0.01"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new CostEstimate(
                "USD",
                BigDecimal.ONE,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("2")));
        assertThrows(IllegalArgumentException.class, () -> CostEstimate.of(" ", BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> CostEstimate.of(
                "C".repeat(CostEstimate.MAX_CURRENCY_LENGTH + 1),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    @Test
    void totalComparesScaleIndependently() {
        var estimate = new CostEstimate(
                "USD",
                new BigDecimal("1.0"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("1.00"));
        assertEquals(0, estimate.total().compareTo(new BigDecimal("1")));
    }
}
