package site.pplee.jcode.ai.message;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;

/**
 * Provider-neutral monetary estimate derived from token usage and an
 * explicit caller-supplied price table. This is not a provider invoice
 * or billed amount.
 *
 * <p>Every amount is a non-null, non-negative {@link BigDecimal}.
 * {@link #total()} must equal the DECIMAL128 sum of the four components.
 * Currency is a short caller-supplied code (for example {@code USD}).
 */
public record CostEstimate(
        String currency,
        BigDecimal input,
        BigDecimal output,
        BigDecimal cacheRead,
        BigDecimal cacheWrite,
        BigDecimal total
) {
    /** Precision used to sum components and to validate {@link #total()}. */
    public static final MathContext MATH_CONTEXT = MathContext.DECIMAL128;

    /** Maximum UTF-16 length of {@link #currency()}. */
    public static final int MAX_CURRENCY_LENGTH = 16;

    public CostEstimate {
        Objects.requireNonNull(currency, "currency must not be null");
        if (currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
        if (currency.length() > MAX_CURRENCY_LENGTH) {
            throw new IllegalArgumentException(
                    "currency exceeds " + MAX_CURRENCY_LENGTH + " characters");
        }
        input = requireAmount("input", input);
        output = requireAmount("output", output);
        cacheRead = requireAmount("cacheRead", cacheRead);
        cacheWrite = requireAmount("cacheWrite", cacheWrite);
        total = requireAmount("total", total);
        BigDecimal expected = sum(input, output, cacheRead, cacheWrite);
        if (total.compareTo(expected) != 0) {
            throw new IllegalArgumentException("total must equal the DECIMAL128 sum of cost components");
        }
    }

    /**
     * Estimate whose total is the DECIMAL128 sum of the four components.
     */
    public static CostEstimate of(
            String currency,
            BigDecimal input,
            BigDecimal output,
            BigDecimal cacheRead,
            BigDecimal cacheWrite
    ) {
        return new CostEstimate(
                currency,
                input,
                output,
                cacheRead,
                cacheWrite,
                sum(input, output, cacheRead, cacheWrite));
    }

    /** DECIMAL128 sum of the four component amounts. */
    public static BigDecimal sum(
            BigDecimal input,
            BigDecimal output,
            BigDecimal cacheRead,
            BigDecimal cacheWrite
    ) {
        return requireAmount("input", input)
                .add(requireAmount("output", output), MATH_CONTEXT)
                .add(requireAmount("cacheRead", cacheRead), MATH_CONTEXT)
                .add(requireAmount("cacheWrite", cacheWrite), MATH_CONTEXT);
    }

    @Override
    public String toString() {
        return "CostEstimate[currency=" + currency
                + ", input=" + input
                + ", output=" + output
                + ", cacheRead=" + cacheRead
                + ", cacheWrite=" + cacheWrite
                + ", total=" + total
                + ", estimate=true]";
    }

    private static BigDecimal requireAmount(String name, BigDecimal amount) {
        Objects.requireNonNull(amount, name + " must not be null");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return amount;
    }
}
