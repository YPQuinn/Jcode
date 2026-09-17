package site.pplee.jcode.aiproviders.openai;

import site.pplee.jcode.ai.message.CostEstimate;
import site.pplee.jcode.ai.message.Usage;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Caller-supplied OpenAI price table. Rates are per million tokens and are
 * never hard-coded for a model catalog. Lookup is by exact model id.
 *
 * <p>Optional {@link ContextThreshold} rows apply when billed input tokens
 * ({@code input + cacheRead + cacheWrite}) are <em>strictly greater than</em>
 * {@code inputTokensAbove}. When several thresholds match, the highest
 * threshold wins. Equality at a threshold keeps the previous (or base) rates.
 *
 * <p>Optional service-tier multipliers scale every component after the
 * per-token calculation. A missing multiplier for the resolved tier is
 * {@code 1}. Arithmetic uses {@link MathContext#DECIMAL128}.
 *
 * <p>{@link #toString()} may show amounts and model ids. It never contains
 * credentials, request headers, or session secrets.
 */
public final class OpenAiPricing {
    /** Precision used for every rate, multiplier, and component calculation. */
    public static final MathContext MATH_CONTEXT = MathContext.DECIMAL128;

    private static final BigDecimal MILLION = new BigDecimal("1000000");

    private final String currency;
    private final Map<String, ModelPrice> models;

    private OpenAiPricing(String currency, Map<String, ModelPrice> models) {
        this.currency = requireCurrency(currency);
        this.models = Map.copyOf(Objects.requireNonNull(models, "models must not be null"));
    }

    /** Price table for {@code currency} keyed by exact model id. */
    public static OpenAiPricing of(String currency, Map<String, ModelPrice> models) {
        return new OpenAiPricing(currency, models);
    }

    public String currency() {
        return currency;
    }

    /** Per-model prices; absent ids are not priced. */
    public Map<String, ModelPrice> models() {
        return models;
    }

    /**
     * Estimate for {@code modelId} and {@code usage}. Empty when this table
     * has no row for the model. Token counts come from {@code usage};
     * an existing {@link Usage#cost()} is ignored.
     */
    public Optional<CostEstimate> estimate(String modelId, Usage usage, Optional<OpenAiServiceTier> serviceTier) {
        Objects.requireNonNull(modelId, "modelId must not be null");
        Objects.requireNonNull(usage, "usage must not be null");
        Objects.requireNonNull(serviceTier, "serviceTier must not be null");
        ModelPrice price = models.get(modelId);
        if (price == null) {
            return Optional.empty();
        }
        TokenRates rates = ratesFor(price, billedInputTokens(usage));
        BigDecimal multiplier = multiplierFor(price, serviceTier);
        BigDecimal input = componentCost(rates.inputPerMillion(), usage.input(), multiplier);
        BigDecimal output = componentCost(rates.outputPerMillion(), usage.output(), multiplier);
        BigDecimal cacheRead = componentCost(rates.cacheReadPerMillion(), usage.cacheRead(), multiplier);
        BigDecimal cacheWrite = componentCost(rates.cacheWritePerMillion(), usage.cacheWrite(), multiplier);
        return Optional.of(CostEstimate.of(currency, input, output, cacheRead, cacheWrite));
    }

    @Override
    public String toString() {
        return "OpenAiPricing[currency=" + currency + ", models=" + models.keySet() + "]";
    }

    static long billedInputTokens(Usage usage) {
        try {
            return Math.addExact(Math.addExact(usage.input(), usage.cacheRead()), usage.cacheWrite());
        } catch (ArithmeticException e) {
            throw new IllegalStateException("protocol mapping error: billed input tokens overflow", e);
        }
    }

    private static TokenRates ratesFor(ModelPrice price, long billedInput) {
        TokenRates rates = price.rates();
        long matched = -1;
        for (ContextThreshold threshold : price.contextThresholds()) {
            if (billedInput > threshold.inputTokensAbove() && threshold.inputTokensAbove() > matched) {
                rates = threshold.rates();
                matched = threshold.inputTokensAbove();
            }
        }
        return rates;
    }

    private static BigDecimal multiplierFor(ModelPrice price, Optional<OpenAiServiceTier> serviceTier) {
        if (serviceTier.isEmpty()) {
            return BigDecimal.ONE;
        }
        return price.serviceTierMultipliers().getOrDefault(serviceTier.get(), BigDecimal.ONE);
    }

    private static BigDecimal componentCost(BigDecimal perMillion, long tokens, BigDecimal multiplier) {
        if (tokens == 0L) {
            return BigDecimal.ZERO;
        }
        return perMillion
                .multiply(BigDecimal.valueOf(tokens), MATH_CONTEXT)
                .divide(MILLION, MATH_CONTEXT)
                .multiply(multiplier, MATH_CONTEXT);
    }

    private static String requireCurrency(String currency) {
        Objects.requireNonNull(currency, "currency must not be null");
        if (currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
        if (currency.length() > CostEstimate.MAX_CURRENCY_LENGTH) {
            throw new IllegalArgumentException(
                    "currency exceeds " + CostEstimate.MAX_CURRENCY_LENGTH + " characters");
        }
        return currency;
    }

    /**
     * Per-million token rates for one pricing row. All amounts are non-null
     * and non-negative.
     */
    public record TokenRates(
            BigDecimal inputPerMillion,
            BigDecimal outputPerMillion,
            BigDecimal cacheReadPerMillion,
            BigDecimal cacheWritePerMillion
    ) {
        public TokenRates {
            inputPerMillion = requireRate("inputPerMillion", inputPerMillion);
            outputPerMillion = requireRate("outputPerMillion", outputPerMillion);
            cacheReadPerMillion = requireRate("cacheReadPerMillion", cacheReadPerMillion);
            cacheWritePerMillion = requireRate("cacheWritePerMillion", cacheWritePerMillion);
        }
    }

    /**
     * Alternate rates that apply when billed input tokens are strictly
     * greater than {@code inputTokensAbove}.
     */
    public record ContextThreshold(long inputTokensAbove, TokenRates rates) {
        public ContextThreshold {
            if (inputTokensAbove < 0) {
                throw new IllegalArgumentException("inputTokensAbove must not be negative");
            }
            Objects.requireNonNull(rates, "rates must not be null");
        }
    }

    /**
     * Price row for one model id: base rates, optional context thresholds,
     * and optional service-tier multipliers.
     */
    public record ModelPrice(
            TokenRates rates,
            List<ContextThreshold> contextThresholds,
            Map<OpenAiServiceTier, BigDecimal> serviceTierMultipliers
    ) {
        public ModelPrice {
            Objects.requireNonNull(rates, "rates must not be null");
            contextThresholds = List.copyOf(Objects.requireNonNull(
                    contextThresholds, "contextThresholds must not be null"));
            serviceTierMultipliers = Map.copyOf(Objects.requireNonNull(
                    serviceTierMultipliers, "serviceTierMultipliers must not be null"));
            for (var entry : serviceTierMultipliers.entrySet()) {
                Objects.requireNonNull(entry.getKey(), "service tier must not be null");
                requireRate("serviceTierMultiplier", entry.getValue());
            }
        }

        /** Base rates only; no context thresholds or service-tier multipliers. */
        public ModelPrice(TokenRates rates) {
            this(rates, List.of(), Map.of());
        }
    }

    private static BigDecimal requireRate(String name, BigDecimal amount) {
        Objects.requireNonNull(amount, name + " must not be null");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return amount;
    }
}
