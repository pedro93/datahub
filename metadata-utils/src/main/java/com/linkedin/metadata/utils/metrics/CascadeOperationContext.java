package com.linkedin.metadata.utils.metrics;

import com.linkedin.common.urn.Urn;
import com.linkedin.mxe.SystemMetadata;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

/**
 * Tracks cascade operation lifecycle for observability. Provides MDC context, Micrometer metrics,
 * and conditional logging for operations that fan out to many entities (e.g., deleteReferencesTo,
 * PropertyDefinitionDeleteSideEffect).
 *
 * <p>Usage:
 *
 * <pre>{@code
 * try (CascadeOperationContext cascade = CascadeOperationContext.begin(
 *         metricUtils, "deleteReferencesTo", triggerUrn, estimatedTotal)) {
 *     for (Entity entity : entities) {
 *         processEntity(entity);
 *         cascade.recordEntityProcessed();
 *     }
 * } // close() emits metrics, conditional log, clears MDC
 * }</pre>
 */
@Slf4j
public class CascadeOperationContext implements AutoCloseable {

  private static final long DEFAULT_SLOW_THRESHOLD_MS = 5000;
  private static final String MDC_CASCADE_OPERATION_ID = "cascade.operation.id";
  private static final String MDC_CASCADE_TRIGGER_URN = "cascade.trigger.urn";
  private static final String MDC_CASCADE_OPERATION_TYPE = "cascade.operation.type";
  private static final String SYSTEM_METADATA_CASCADE_ID_KEY = "cascadeOperationId";

  private final @Nullable MetricUtils metricUtils;
  private final String operationType;
  private final String triggerUrnType;
  private final String operationId;
  private final long startNanos;
  private final long slowThresholdMs;
  private final AtomicInteger entitiesProcessed = new AtomicInteger(0);
  private final AtomicInteger errorCount = new AtomicInteger(0);
  private volatile String lastErrorType;

  private CascadeOperationContext(
      @Nullable MetricUtils metricUtils,
      String operationType,
      Urn triggerUrn,
      int estimatedTotal,
      long slowThresholdMs) {
    this.metricUtils = metricUtils;
    this.operationType = operationType;
    this.triggerUrnType = triggerUrn != null ? triggerUrn.getEntityType() : "unknown";
    this.operationId = UUID.randomUUID().toString();
    this.startNanos = System.nanoTime();
    this.slowThresholdMs = slowThresholdMs;

    MDC.put(MDC_CASCADE_OPERATION_ID, operationId);
    MDC.put(MDC_CASCADE_TRIGGER_URN, triggerUrn != null ? triggerUrn.toString() : "unknown");
    MDC.put(MDC_CASCADE_OPERATION_TYPE, operationType);

    log.debug(
        "Cascade started: type={}, triggerUrn={}, estimatedEntities={}",
        operationType,
        triggerUrn,
        estimatedTotal);
  }

  /**
   * Begin tracking a cascade operation.
   *
   * @param metricUtils Micrometer metric utilities (nullable — metrics become no-ops if null)
   * @param operationType identifies the cascade type (e.g., "deleteReferencesTo")
   * @param triggerUrn the URN that triggered the cascade
   * @param estimatedTotal estimated number of entities to process (for logging only)
   * @return a new context that should be closed when the cascade completes
   */
  public static CascadeOperationContext begin(
      @Nullable MetricUtils metricUtils, String operationType, Urn triggerUrn, int estimatedTotal) {
    return new CascadeOperationContext(
        metricUtils, operationType, triggerUrn, estimatedTotal, DEFAULT_SLOW_THRESHOLD_MS);
  }

  /** Record that one entity was successfully processed. */
  public void recordEntityProcessed() {
    entitiesProcessed.incrementAndGet();
  }

  /**
   * Record an error during cascade processing.
   *
   * @param errorType a low-cardinality error type string for metric tagging
   */
  public void recordError(String errorType) {
    errorCount.incrementAndGet();
    this.lastErrorType = errorType;
  }

  /**
   * Attach the cascade operation ID to a SystemMetadata instance for cross-service correlation.
   * Mutates the SystemMetadata in-place. Safe to call on PatchItemImpl's SystemMetadata since both
   * the item and its backing MCP share the same object reference.
   *
   * @param systemMetadata the metadata to annotate (properties map created if null)
   */
  public void attachToSystemMetadata(@Nullable SystemMetadata systemMetadata) {
    if (systemMetadata == null) {
      return;
    }
    if (systemMetadata.getProperties() == null) {
      systemMetadata.setProperties(new com.linkedin.data.template.StringMap());
    }
    systemMetadata.getProperties().put(SYSTEM_METADATA_CASCADE_ID_KEY, operationId);
  }

  /** Returns the cascade operation ID for this context. */
  public String getOperationId() {
    return operationId;
  }

  /**
   * Emit metrics and conditional log, then clear MDC. Never throws — metric/logging failures are
   * caught internally to avoid breaking the cascade operation.
   */
  @Override
  public void close() {
    try {
      long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
      int processed = entitiesProcessed.get();
      int errors = errorCount.get();
      String status = errors > 0 ? "completed_with_errors" : "completed";

      // Emit metrics (always, regardless of log level)
      if (metricUtils != null) {
        metricUtils.recordTimer(
            "datahub.cascade.duration",
            System.nanoTime() - startNanos,
            "operation_type",
            operationType,
            "trigger_urn_type",
            triggerUrnType,
            "status",
            status);
        metricUtils.incrementMicrometer(
            "datahub.cascade.entities_processed",
            processed,
            "operation_type",
            operationType,
            "trigger_urn_type",
            triggerUrnType);
        if (errors > 0) {
          metricUtils.incrementMicrometer(
              "datahub.cascade.errors",
              errors,
              "operation_type",
              operationType,
              "trigger_urn_type",
              triggerUrnType,
              "error_type",
              lastErrorType != null ? lastErrorType : "unknown");
        }
      }

      // Conditional logging per PR #16577/#16578 guidelines
      if (errors > 0) {
        log.warn(
            "Cascade completed with errors: type={}, entities={}, errors={}, duration={}ms",
            operationType,
            processed,
            errors,
            durationMs);
      } else if (durationMs >= slowThresholdMs) {
        log.info(
            "Cascade completed (slow): type={}, entities={}, duration={}ms",
            operationType,
            processed,
            durationMs);
      } else {
        log.debug(
            "Cascade completed: type={}, entities={}, duration={}ms",
            operationType,
            processed,
            durationMs);
      }
    } catch (Exception e) {
      // Never propagate — observability failures must not break the cascade
      log.debug("Failed to emit cascade metrics", e);
    } finally {
      MDC.remove(MDC_CASCADE_OPERATION_ID);
      MDC.remove(MDC_CASCADE_TRIGGER_URN);
      MDC.remove(MDC_CASCADE_OPERATION_TYPE);
    }
  }
}
