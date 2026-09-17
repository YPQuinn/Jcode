package site.pplee.jcode.aiproviders.openai;

import org.junit.jupiter.api.Test;
import site.pplee.jcode.ai.message.CostEstimate;
import site.pplee.jcode.ai.message.Usage;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiPricingTest {
    private static final String MODEL = "gpt-4o-mini";

    private static OpenAiPricing.TokenRates rates(String input, String output, String cacheRead, String cacheWrite) {
        return new OpenAiPricing.TokenRates(
                new BigDecimal(input),
                new BigDecimal(output),
                new BigDecimal(cacheRead),
                new BigDecimal(cacheWrite));
    }

    @Test
    void unknownModelOrMissingTableYieldsAbsentCost() {
        var pricing = OpenAiPricing.of("USD", Map.of(MODEL, new OpenAiPricing.ModelPrice(rates("1", "2", "0.1", "0.5"))));
        var usage = new Usage(1_000_000, 1_000_000, 0, 0, 2_000_000);
        assertTrue(pricing.estimate("other-model", usage, Optional.empty()).isEmpty());
        assertTrue(Usage.zero().cost().isEmpty());
    }

    @Test
    void ordinaryAndCacheComponentsSumToTotal() {
        var pricing = OpenAiPricing.of("USD", Map.of(MODEL, new OpenAiPricing.ModelPrice(rates("1", "2", "0.1", "0.5"))));
        var usage = new Usage(1_000_000, 500_000, 200_000, 100_000, 1_800_000);
        var cost = pricing.estimate(MODEL, usage, Optional.empty()).orElseThrow();
        assertEquals(0, cost.input().compareTo(BigDecimal.ONE));
        assertEquals(0, cost.output().compareTo(BigDecimal.ONE));
        assertEquals(0, cost.cacheRead().compareTo(new BigDecimal("0.02")));
        assertEquals(0, cost.cacheWrite().compareTo(new BigDecimal("0.05")));
        assertEquals(0, cost.total().compareTo(new BigDecimal("2.07")));
        assertEquals("USD", cost.currency());
        assertEquals(0, cost.total().compareTo(CostEstimate.sum(
                cost.input(), cost.output(), cost.cacheRead(), cost.cacheWrite())));
    }

    @Test
    void contextThresholdIsStrictlyGreaterThan() {
        var base = rates("1", "2", "0", "0");
        var high = rates("4", "2", "0", "0");
        var pricing = OpenAiPricing.of("USD", Map.of(MODEL, new OpenAiPricing.ModelPrice(
                base,
                List.of(new OpenAiPricing.ContextThreshold(128_000, high)),
                Map.of())));
        var atThreshold = new Usage(128_000, 0, 0, 0, 128_000);
        var above = new Usage(128_001, 0, 0, 0, 128_001);
        assertEquals(0, pricing.estimate(MODEL, atThreshold, Optional.empty()).orElseThrow()
                .input().compareTo(new BigDecimal("0.128")));
        assertEquals(0, pricing.estimate(MODEL, above, Optional.empty()).orElseThrow()
                .input().compareTo(new BigDecimal("0.512004")));
        assertEquals(128_000 + 10_000 + 5_000, OpenAiPricing.billedInputTokens(
                new Usage(128_000, 0, 10_000, 5_000, 143_000)));
        assertThrows(IllegalStateException.class, () -> OpenAiPricing.billedInputTokens(
                new Usage(Long.MAX_VALUE, 0, 1, 0, Long.MAX_VALUE)));
        assertThrows(IllegalStateException.class, () -> pricing.estimate(
                MODEL, new Usage(Long.MAX_VALUE, 0, 1, 0, Long.MAX_VALUE), Optional.empty()));
    }

    @Test
    void serviceTierMultiplierScalesEveryComponent() {
        var pricing = OpenAiPricing.of("USD", Map.of(MODEL, new OpenAiPricing.ModelPrice(
                rates("1", "2", "0.1", "0.5"),
                List.of(),
                Map.of(OpenAiServiceTier.FLEX, new BigDecimal("0.5")))));
        var usage = new Usage(1_000_000, 1_000_000, 1_000_000, 1_000_000, 4_000_000);
        var cost = pricing.estimate(MODEL, usage, Optional.of(OpenAiServiceTier.FLEX)).orElseThrow();
        assertEquals(0, cost.input().compareTo(new BigDecimal("0.5")));
        assertEquals(0, cost.output().compareTo(BigDecimal.ONE));
        assertEquals(0, cost.cacheRead().compareTo(new BigDecimal("0.05")));
        assertEquals(0, cost.cacheWrite().compareTo(new BigDecimal("0.25")));
        assertEquals(0, cost.total().compareTo(new BigDecimal("1.80")));
        var unconfiguredTier = pricing.estimate(MODEL, usage, Optional.of(OpenAiServiceTier.PRIORITY)).orElseThrow();
        assertEquals(0, unconfiguredTier.total().compareTo(new BigDecimal("3.60")));
    }

    @Test
    void decimal128AvoidsBinaryDoubleDrift() {
        var pricing = OpenAiPricing.of("USD", Map.of(MODEL, new OpenAiPricing.ModelPrice(rates("0.1", "0", "0", "0"))));
        var usage = new Usage(3, 0, 0, 0, 3);
        var cost = pricing.estimate(MODEL, usage, Optional.empty()).orElseThrow();
        var expected = new BigDecimal("0.1")
                .multiply(BigDecimal.valueOf(3), OpenAiPricing.MATH_CONTEXT)
                .divide(new BigDecimal("1000000"), OpenAiPricing.MATH_CONTEXT);
        assertEquals(0, cost.input().compareTo(expected));
        assertEquals(0, cost.total().compareTo(expected));
        assertEquals(java.math.MathContext.DECIMAL128.getPrecision(), OpenAiPricing.MATH_CONTEXT.getPrecision());
        assertEquals(java.math.MathContext.DECIMAL128, CostEstimate.MATH_CONTEXT);
    }

    @Test
    void toStringShowsCurrencyAndModelIdsButNotSecrets() {
        var pricing = OpenAiPricing.of("USD", Map.of(MODEL, new OpenAiPricing.ModelPrice(rates("1", "2", "0", "0"))));
        assertTrue(pricing.toString().contains("USD"));
        assertTrue(pricing.toString().contains(MODEL));
        assertFalse(pricing.toString().contains("sk-"));
        assertThrows(IllegalArgumentException.class, () -> OpenAiPricing.of(" ", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new OpenAiPricing.TokenRates(new BigDecimal("-1"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
    }
}
