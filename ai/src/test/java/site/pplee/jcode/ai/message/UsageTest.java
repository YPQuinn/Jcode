package site.pplee.jcode.ai.message;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsageTest {

    @Test
    void fiveArgumentConstructorAndZeroLeaveCostAbsent() {
        var compat = new Usage(10, 4, 2, 1, 17);
        assertEquals(0, compat.reasoningTokens());
        assertEquals(Optional.empty(), compat.cost());
        var zero = Usage.zero();
        assertEquals(0, zero.input());
        assertEquals(0, zero.output());
        assertEquals(0, zero.reasoningTokens());
        assertEquals(Optional.empty(), zero.cost());
        assertEquals(compat, new Usage(10, 4, 2, 1, 17, 0, Optional.empty()));
    }

    @Test
    void reasoningMustBeNonNegativeSubsetOfOutput() {
        var usage = new Usage(1, 8, 0, 0, 9, 3, Optional.empty());
        assertEquals(3, usage.reasoningTokens());
        assertThrows(IllegalArgumentException.class, () -> new Usage(1, 2, 0, 0, 3, 3, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new Usage(1, 2, 0, 0, 3, -1, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new Usage(-1, 0, 0, 0, 0));
        assertThrows(NullPointerException.class, () -> new Usage(1, 1, 0, 0, 2, 0, null));
    }

    @Test
    void withCostDoesNotUseZeroAsUnpricedSentinel() {
        var cost = CostEstimate.of("USD", new BigDecimal("0.01"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        var priced = new Usage(10, 0, 0, 0, 10).withCost(cost);
        assertEquals(Optional.of(cost), priced.cost());
        assertTrue(Usage.zero().cost().isEmpty());
    }
}
