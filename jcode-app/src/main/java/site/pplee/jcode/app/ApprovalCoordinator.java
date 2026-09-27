package site.pplee.jcode.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import site.pplee.jcode.ai.concurrent.CancellationRegistration;
import site.pplee.jcode.ai.concurrent.CancellationSignal;
import site.pplee.jcode.codingagent.tool.CodingToolPolicy;
import site.pplee.jcode.codingagent.tool.CodingToolRequest;
import site.pplee.jcode.protocol.ApprovalCommand;
import site.pplee.jcode.protocol.ApprovalDecision;
import site.pplee.jcode.protocol.ApprovalStatus;
import site.pplee.jcode.protocol.ApprovalView;
import site.pplee.jcode.protocol.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** One-time tool decisions sharing the owning ManagedSession's state lock. */
final class ApprovalCoordinator {
    private static final int MAX_PENDING = 64;
    private static final int MAX_RETAINED = 256;
    private static final int MAX_DESCRIPTION_CHARS = 2_048;

    private final Object lock;
    private final SessionFeed feed;
    private final String sessionId;
    private final ApprovalSettings settings;
    private final Map<String, PendingApproval> approvals = new LinkedHashMap<>();

    ApprovalCoordinator(Object lock, SessionFeed feed, String sessionId, ApprovalSettings settings) {
        this.lock = Objects.requireNonNull(lock);
        this.feed = Objects.requireNonNull(feed);
        this.sessionId = Objects.requireNonNull(sessionId);
        this.settings = Objects.requireNonNull(settings);
    }

    ApprovalView resolve(ApprovalCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        PendingApproval approval;
        CodingToolPolicy.Decision result;
        synchronized (lock) {
            approval = approvals.get(command.approvalId());
            if (approval == null) {
                throw error(ErrorCode.NOT_FOUND, "approval is not retained");
            }
            if (!approval.view.toolCallId().equals(command.toolCallId())
                    || !approval.view.requestDigest().equals(command.requestDigest())) {
                throw error(ErrorCode.STATE_CONFLICT, "approval request has changed");
            }
            var requested = command.decision() == ApprovalDecision.ALLOW
                    ? ApprovalStatus.ALLOWED : ApprovalStatus.DENIED;
            if (approval.view.status() == requested) {
                return approval.view;
            }
            if (approval.view.status().terminal()) {
                throw error(ErrorCode.APPROVAL_CLOSED, "approval has already been settled");
            }
            change(approval, requested);
            result = requested == ApprovalStatus.ALLOWED
                    ? new CodingToolPolicy.Decision.Allow()
                    : new CodingToolPolicy.Decision.Deny("tool request denied by user");
        }
        approval.closeCancellationRegistration();
        approval.result.complete(result);
        return approval.view;
    }

    Optional<ApprovalView> view(String approvalId) {
        Objects.requireNonNull(approvalId, "approvalId must not be null");
        synchronized (lock) {
            var approval = approvals.get(approvalId);
            return approval == null ? Optional.empty() : Optional.of(approval.view);
        }
    }

    CompletionStage<CodingToolPolicy.Decision> request(
            CodingToolRequest request,
            CancellationSignal cancellation,
            Supplier<String> activeRunId
    ) {
        if (cancellation.isCancelled()) {
            return CompletableFuture.completedStage(
                    new CodingToolPolicy.Decision.Deny("run was cancelled"));
        }
        String digest = digest(request);
        String description = description(request);
        PendingApproval approval;
        synchronized (lock) {
            String runId = activeRunId.get();
            if (runId == null) {
                return CompletableFuture.completedStage(
                        new CodingToolPolicy.Decision.Deny("run is no longer active"));
            }
            long pending = approvals.values().stream()
                    .filter(item -> item.view.status() == ApprovalStatus.PENDING).count();
            if (pending >= MAX_PENDING) {
                return CompletableFuture.failedStage(
                        error(ErrorCode.CAPACITY_EXCEEDED, "too many pending approvals"));
            }
            if (approvals.size() >= MAX_RETAINED) {
                for (var iterator = approvals.entrySet().iterator(); iterator.hasNext();) {
                    if (iterator.next().getValue().view.status().terminal()) {
                        iterator.remove();
                        break;
                    }
                }
            }
            String approvalId = "approval_" + UUID.randomUUID();
            var view = new ApprovalView(sessionId, approvalId, runId,
                    request.toolCallId(), request.toolName(), digest, description,
                    ApprovalStatus.PENDING);
            approval = new PendingApproval(view);
            approvals.put(approvalId, approval);
            feed.publish(view);
        }
        var registration = cancellation.onCancellation(
                () -> settle(approval, ApprovalStatus.CANCELLED, "run was cancelled"));
        approval.registration = registration;
        if (approval.view.status().terminal()) {
            registration.close();
        }
        CompletableFuture.delayedExecutor(settings.timeout().toMillis(), TimeUnit.MILLISECONDS)
                .execute(() -> settle(approval, ApprovalStatus.EXPIRED, "tool approval timed out"));
        return approval.result.copy();
    }

    /** Mark while the shared lock is held; returned completions run after the lock is released. */
    List<Runnable> markCancelled(String runId) {
        var completions = new ArrayList<Runnable>();
        for (var approval : approvals.values()) {
            if (approval.view.runId().equals(runId)
                    && approval.view.status() == ApprovalStatus.PENDING) {
                change(approval, ApprovalStatus.CANCELLED);
                completions.add(() -> {
                    approval.closeCancellationRegistration();
                    approval.result.complete(new CodingToolPolicy.Decision.Deny("run was cancelled"));
                });
            }
        }
        return completions;
    }

    private void settle(PendingApproval approval, ApprovalStatus status, String reason) {
        synchronized (lock) {
            if (approval.view.status().terminal()) {
                return;
            }
            change(approval, status);
        }
        approval.closeCancellationRegistration();
        approval.result.complete(new CodingToolPolicy.Decision.Deny(reason));
    }

    private void change(PendingApproval approval, ApprovalStatus status) {
        var previous = approval.view;
        approval.view = new ApprovalView(sessionId, previous.approvalId(), previous.runId(),
                previous.toolCallId(), previous.toolName(), previous.requestDigest(),
                previous.description(), status);
        feed.publish(approval.view);
    }

    private static String digest(CodingToolRequest request) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            hash.update(request.toolCallId().getBytes(StandardCharsets.UTF_8));
            hash.update((byte) 0);
            hash.update(request.toolName().getBytes(StandardCharsets.UTF_8));
            hash.update((byte) 0);
            hash.update(request.preparedArguments().toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String description(CodingToolRequest request) {
        String text = redact(request.preparedArguments()).toString();
        if (text.length() <= MAX_DESCRIPTION_CHARS) {
            return text;
        }
        int end = MAX_DESCRIPTION_CHARS;
        if (Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return text.substring(0, end);
    }

    private static JsonNode redact(JsonNode source) {
        if (source instanceof ObjectNode object) {
            var copy = object.objectNode();
            object.properties().forEach(property -> {
                String key = property.getKey().toLowerCase(Locale.ROOT);
                boolean secret = key.contains("password") || key.contains("secret")
                        || key.contains("token") || key.contains("credential")
                        || key.contains("authorization") || key.contains("api_key")
                        || key.contains("apikey");
                copy.set(property.getKey(), secret
                        ? TextNode.valueOf("[redacted]") : redact(property.getValue()));
            });
            return copy;
        }
        if (source instanceof ArrayNode array) {
            var copy = array.arrayNode();
            array.forEach(value -> copy.add(redact(value)));
            return copy;
        }
        return source.deepCopy();
    }

    private static ApiException error(ErrorCode code, String message) {
        return new ApiException(code, message);
    }

    private static final class PendingApproval {
        private final CompletableFuture<CodingToolPolicy.Decision> result = new CompletableFuture<>();
        private volatile ApprovalView view;
        private volatile CancellationRegistration registration;

        private PendingApproval(ApprovalView view) {
            this.view = view;
        }

        private void closeCancellationRegistration() {
            var current = registration;
            if (current != null) {
                current.close();
            }
        }
    }
}
