package com.linkedin.metadata.utils.metrics;

import static org.testng.Assert.*;

import com.linkedin.common.urn.Urn;
import com.linkedin.common.urn.UrnUtils;
import com.linkedin.mxe.SystemMetadata;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.MDC;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class CascadeOperationContextTest {

  private SimpleMeterRegistry meterRegistry;
  private MetricUtils metricUtils;

  @BeforeMethod
  public void setup() {
    MDC.clear();
    meterRegistry = new SimpleMeterRegistry();
    metricUtils = MetricUtils.builder().registry(meterRegistry).build();
  }

  @AfterMethod
  public void cleanup() {
    MDC.clear();
  }

  @Test
  public void testMDCSetOnBeginAndClearedOnClose() {
    Urn triggerUrn = UrnUtils.getUrn("urn:li:tag:testTag");

    try (CascadeOperationContext ctx =
        CascadeOperationContext.begin(metricUtils, "deleteReferencesTo", triggerUrn, 100)) {
      assertEquals(MDC.get("cascade.operation.type"), "deleteReferencesTo");
      assertEquals(MDC.get("cascade.trigger.urn"), "urn:li:tag:testTag");
      assertNotNull(MDC.get("cascade.operation.id"));
    }

    assertNull(MDC.get("cascade.operation.id"));
    assertNull(MDC.get("cascade.trigger.urn"));
    assertNull(MDC.get("cascade.operation.type"));
  }

  @Test
  public void testCloseWithMetricsDoesNotThrow() {
    Urn triggerUrn = UrnUtils.getUrn("urn:li:tag:testTag");

    // Verify that close() completes without exception when recording metrics
    try (CascadeOperationContext ctx =
        CascadeOperationContext.begin(metricUtils, "deleteReferencesTo", triggerUrn, 50)) {
      ctx.recordEntityProcessed();
      ctx.recordEntityProcessed();
      ctx.recordEntityProcessed();
    }
    // If we get here, close() succeeded — metrics were emitted without error
    assertNull(MDC.get("cascade.operation.id"), "MDC should be cleared after close");
  }

  @Test
  public void testCloseWithErrorsDoesNotThrow() {
    Urn triggerUrn = UrnUtils.getUrn("urn:li:tag:testTag");

    // Verify that close() with errors completes without exception
    try (CascadeOperationContext ctx =
        CascadeOperationContext.begin(metricUtils, "deleteReferencesTo", triggerUrn, 10)) {
      ctx.recordEntityProcessed();
      ctx.recordError("clone_failed");
    }
    // If we get here, close() succeeded with error metrics
    assertNull(MDC.get("cascade.operation.id"), "MDC should be cleared after close");
  }

  @Test
  public void testNullMetricUtilsHandledGracefully() {
    Urn triggerUrn = UrnUtils.getUrn("urn:li:tag:testTag");

    // Should not throw
    try (CascadeOperationContext ctx =
        CascadeOperationContext.begin(null, "deleteReferencesTo", triggerUrn, 10)) {
      ctx.recordEntityProcessed();
      ctx.recordError("test_error");
    }

    // MDC should still be cleared
    assertNull(MDC.get("cascade.operation.id"));
  }

  @Test
  public void testAttachToSystemMetadataWithNullProperties() {
    Urn triggerUrn = UrnUtils.getUrn("urn:li:tag:testTag");
    SystemMetadata systemMetadata = new SystemMetadata();
    assertNull(systemMetadata.getProperties());

    try (CascadeOperationContext ctx =
        CascadeOperationContext.begin(metricUtils, "deleteReferencesTo", triggerUrn, 1)) {
      ctx.attachToSystemMetadata(systemMetadata);

      assertNotNull(systemMetadata.getProperties());
      assertEquals(systemMetadata.getProperties().get("cascadeOperationId"), ctx.getOperationId());
    }
  }

  @Test
  public void testAttachToSystemMetadataWithExistingProperties() {
    Urn triggerUrn = UrnUtils.getUrn("urn:li:tag:testTag");
    SystemMetadata systemMetadata = new SystemMetadata();
    systemMetadata.setProperties(new com.linkedin.data.template.StringMap());
    systemMetadata.getProperties().put("existingKey", "existingValue");

    try (CascadeOperationContext ctx =
        CascadeOperationContext.begin(metricUtils, "deleteReferencesTo", triggerUrn, 1)) {
      ctx.attachToSystemMetadata(systemMetadata);

      assertEquals(systemMetadata.getProperties().get("existingKey"), "existingValue");
      assertEquals(systemMetadata.getProperties().get("cascadeOperationId"), ctx.getOperationId());
    }
  }

  @Test
  public void testAttachToNullSystemMetadataDoesNotThrow() {
    Urn triggerUrn = UrnUtils.getUrn("urn:li:tag:testTag");

    try (CascadeOperationContext ctx =
        CascadeOperationContext.begin(metricUtils, "deleteReferencesTo", triggerUrn, 1)) {
      // Should not throw
      ctx.attachToSystemMetadata(null);
    }
  }

  @Test
  public void testOperationIdIsUnique() {
    Urn triggerUrn = UrnUtils.getUrn("urn:li:tag:testTag");

    String id1;
    String id2;
    try (CascadeOperationContext ctx1 =
        CascadeOperationContext.begin(metricUtils, "deleteReferencesTo", triggerUrn, 1)) {
      id1 = ctx1.getOperationId();
    }
    try (CascadeOperationContext ctx2 =
        CascadeOperationContext.begin(metricUtils, "deleteReferencesTo", triggerUrn, 1)) {
      id2 = ctx2.getOperationId();
    }

    assertNotEquals(id1, id2);
  }
}
