package site.pplee.jcode.agentcore.support;

import site.pplee.jcode.agentcore.message.AgentMessage;

/**
 * Test-only custom {@link AgentMessage} implementation used to verify that the
 * default {@code MessageProjector} filters unknown product messages and that a
 * custom projector can project them to standard {@code ai.Message}s.
 */
public record ProductMessage(String text) implements AgentMessage {}
