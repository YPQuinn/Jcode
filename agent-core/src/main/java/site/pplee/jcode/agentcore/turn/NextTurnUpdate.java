package site.pplee.jcode.agentcore.turn;

import site.pplee.jcode.agentcore.AgentContext;

import site.pplee.jcode.ai.model.ModelRef;
import site.pplee.jcode.ai.model.ThinkingLevel;

import java.util.Objects;
import java.util.Optional;

/**
 * Patch for the next model turn, returned by {@link PrepareNextTurn}. An
 * empty component keeps the current value; a present component replaces it.
 * This makes thinking control three-valued: {@code empty} keeps the current
 * level, {@link ThinkingLevel#PROVIDER_DEFAULT} resets to the provider
 * default, and any other level sets an absolute preference.
 *
 * <p>The patch is validated completely at construction and then applied
 * atomically to the run state: either all present components take effect for
 * the next turn or none do.
 */
public record NextTurnUpdate(
        Optional<AgentContext> context,
        Optional<ModelRef> model,
        Optional<ThinkingLevel> thinkingLevel
) {
    public NextTurnUpdate {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(thinkingLevel, "thinkingLevel must not be null");
        context.ifPresent(c -> Objects.requireNonNull(c, "context value must not be null"));
        model.ifPresent(m -> Objects.requireNonNull(m, "model value must not be null"));
        thinkingLevel.ifPresent(l -> Objects.requireNonNull(l, "thinkingLevel value must not be null"));
    }

    /** An empty patch: keep the current context, model, and thinking level. */
    public static NextTurnUpdate keep() {
        return new NextTurnUpdate(Optional.empty(), Optional.empty(), Optional.empty());
    }
}
