package com.wavefront.agent.listeners.tracing;

import com.google.common.annotations.VisibleForTesting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wavefront.agent.auth.TokenAuthenticator;
import com.wavefront.agent.channel.HealthCheckManager;
import com.wavefront.agent.formatter.DataFormat;
import com.wavefront.agent.handlers.HandlerKey;
import com.wavefront.agent.handlers.ReportableEntityHandler;
import com.wavefront.agent.handlers.ReportableEntityHandlerFactory;
import com.wavefront.agent.listeners.AbstractLineDelimitedHandler;
import com.wavefront.agent.preprocessor.ReportableEntityPreprocessor;
import com.wavefront.data.ReportableEntityType;
import com.wavefront.ingester.ReportableEntityDecoder;
import com.wavefront.sdk.entities.tracing.sampling.Sampler;
import com.yammer.metrics.Metrics;
import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.MetricName;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.util.CharsetUtil;
import wavefront.report.Span;
import wavefront.report.SpanLogs;

import static com.wavefront.agent.channel.ChannelUtils.formatErrorMessage;
import static com.wavefront.agent.listeners.FeatureCheckUtils.SPANLOGS_DISABLED;
import static com.wavefront.agent.listeners.FeatureCheckUtils.SPAN_DISABLED;
import static com.wavefront.agent.listeners.FeatureCheckUtils.isFeatureDisabled;
import static com.wavefront.agent.listeners.tracing.SpanDerivedMetricsUtils.ERROR_SPAN_TAG_KEY;
import static com.wavefront.agent.listeners.tracing.SpanDerivedMetricsUtils.ERROR_SPAN_TAG_VAL;

/**
 * Process incoming trace-formatted data.
 *
 * Accepts incoming messages of either String or FullHttpRequest type: single Span in a string,
 * or multiple points in the HTTP post body, newline-delimited.
 *
 * @author vasily@wavefront.com
 */
@ChannelHandler.Sharable
public class TracePortUnificationHandler extends AbstractLineDelimitedHandler {
  private static final Logger logger = Logger.getLogger(
      TracePortUnificationHandler.class.getCanonicalName());

  private static final ObjectMapper JSON_PARSER = new ObjectMapper();

  protected final ReportableEntityHandler<Span, String> handler;
  private final ReportableEntityHandler<SpanLogs, String> spanLogsHandler;
  private final ReportableEntityDecoder<String, Span> decoder;
  private final ReportableEntityDecoder<JsonNode, SpanLogs> spanLogsDecoder;
  private final Supplier<ReportableEntityPreprocessor> preprocessorSupplier;
  private final Sampler sampler;
  protected final boolean alwaysSampleErrors;
  private final Supplier<Boolean> traceDisabled;
  private final Supplier<Boolean> spanLogsDisabled;

  protected final Counter discardedSpans;
  protected final Counter discardedSpanLogs;
  private final Counter discardedSpansBySampler;
  private final Counter discardedSpanLogsBySampler;

  public TracePortUnificationHandler(
      final String handle, final TokenAuthenticator tokenAuthenticator,
      final HealthCheckManager healthCheckManager,
      final ReportableEntityDecoder<String, Span> traceDecoder,
      final ReportableEntityDecoder<JsonNode, SpanLogs> spanLogsDecoder,
      @Nullable final Supplier<ReportableEntityPreprocessor> preprocessor,
      final ReportableEntityHandlerFactory handlerFactory, final Sampler sampler,
      final boolean alwaysSampleErrors, final Supplier<Boolean> traceDisabled,
      final Supplier<Boolean> spanLogsDisabled) {
    this(handle, tokenAuthenticator, healthCheckManager, traceDecoder, spanLogsDecoder,
        preprocessor, handlerFactory.getHandler(HandlerKey.of(ReportableEntityType.TRACE, handle)),
        handlerFactory.getHandler(HandlerKey.of(ReportableEntityType.TRACE_SPAN_LOGS, handle)),
        sampler, alwaysSampleErrors, traceDisabled, spanLogsDisabled);
  }

  @VisibleForTesting
  public TracePortUnificationHandler(
      final String handle, final TokenAuthenticator tokenAuthenticator,
      final HealthCheckManager healthCheckManager,
      final ReportableEntityDecoder<String, Span> traceDecoder,
      final ReportableEntityDecoder<JsonNode, SpanLogs> spanLogsDecoder,
      @Nullable final Supplier<ReportableEntityPreprocessor> preprocessor,
      final ReportableEntityHandler<Span, String> handler,
      final ReportableEntityHandler<SpanLogs, String> spanLogsHandler, final Sampler sampler,
      final boolean alwaysSampleErrors, final Supplier<Boolean> traceDisabled,
      final Supplier<Boolean> spanLogsDisabled) {
    super(tokenAuthenticator, healthCheckManager, handle);
    this.decoder = traceDecoder;
    this.spanLogsDecoder = spanLogsDecoder;
    this.handler = handler;
    this.spanLogsHandler = spanLogsHandler;
    this.preprocessorSupplier = preprocessor;
    this.sampler = sampler;
    this.alwaysSampleErrors = alwaysSampleErrors;
    this.traceDisabled = traceDisabled;
    this.spanLogsDisabled = spanLogsDisabled;
    this.discardedSpans = Metrics.newCounter(new MetricName("spans." + handle, "", "discarded"));
    this.discardedSpanLogs = Metrics.newCounter(new MetricName("spanLogs." + handle, "",
        "discarded"));
    this.discardedSpansBySampler = Metrics.newCounter(new MetricName("spans." + handle, "",
        "sampler.discarded"));
    this.discardedSpanLogsBySampler = Metrics.newCounter(new MetricName("spanLogs." + handle, "",
        "sampler.discarded"));
  }

  @Nullable
  @Override
  protected DataFormat getFormat(FullHttpRequest httpRequest) {
    return DataFormat.parse(URLEncodedUtils.parse(URI.create(httpRequest.uri()), CharsetUtil.UTF_8).
        stream().filter(x -> x.getName().equals("format") || x.getName().equals("f")).
        map(NameValuePair::getValue).findFirst().orElse(null));
  }

  @Override
  protected void processLine(final ChannelHandlerContext ctx, @Nonnull String message,
                             @Nullable DataFormat format) {
    if (format == DataFormat.SPAN_LOG || (message.startsWith("{") && message.endsWith("}"))) {
      if (isFeatureDisabled(spanLogsDisabled, SPANLOGS_DISABLED, discardedSpanLogs)) return;
      handleSpanLogs(message, spanLogsDecoder, decoder, spanLogsHandler, preprocessorSupplier,
          ctx, alwaysSampleErrors, this::sampleSpanLogs);
      return;
    }
    if (isFeatureDisabled(traceDisabled, SPAN_DISABLED, discardedSpans)) return;
    preprocessAndHandleSpan(message, decoder, handler, this::report, preprocessorSupplier, ctx,
        alwaysSampleErrors, this::sample);
  }

  public static void preprocessAndHandleSpan(
      String message, ReportableEntityDecoder<String, Span> decoder,
      ReportableEntityHandler<Span, String> handler, Consumer<Span> spanReporter,
      @Nullable Supplier<ReportableEntityPreprocessor> preprocessorSupplier,
      @Nullable ChannelHandlerContext ctx, boolean alwaysSampleErrors,
      Function<Span, Boolean> samplerFunc) {
    ReportableEntityPreprocessor preprocessor = preprocessorSupplier == null ?
        null : preprocessorSupplier.get();
    String[] messageHolder = new String[1];

    // transform the line if needed
    if (preprocessor != null) {
      message = preprocessor.forPointLine().transform(message);

      if (!preprocessor.forPointLine().filter(message, messageHolder)) {
        if (messageHolder[0] != null) {
          handler.reject((Span) null, messageHolder[0]);
        } else {
          handler.block(null, message);
        }
        return;
      }
    }
    List<Span> output = new ArrayList<>(1);
    try {
      decoder.decode(message, output, "dummy");
    } catch (Exception e) {
      handler.reject(message, formatErrorMessage(message, e, ctx));
      return;
    }

    for (Span object : output) {
      if (preprocessor != null) {
        preprocessor.forSpan().transform(object);
        if (!preprocessor.forSpan().filter(object, messageHolder)) {
          if (messageHolder[0] != null) {
            handler.reject(object, messageHolder[0]);
          } else {
            handler.block(object);
          }
          return;
        }
      }
      // check whether error span tag exists.
      boolean sampleError = alwaysSampleErrors && object.getAnnotations().stream().anyMatch(t ->
          t.getKey().equals(ERROR_SPAN_TAG_KEY) && t.getValue().equals(ERROR_SPAN_TAG_VAL));
      if (sampleError || samplerFunc.apply(object)) {
        spanReporter.accept(object);
      }
    }
  }

  public static void handleSpanLogs(
      String message, ReportableEntityDecoder<JsonNode, SpanLogs> spanLogsDecoder,
      ReportableEntityDecoder<String, Span> spanDecoder,
      ReportableEntityHandler<SpanLogs, String> handler,
      @Nullable Supplier<ReportableEntityPreprocessor> preprocessorSupplier,
      @Nullable ChannelHandlerContext ctx, boolean alwaysSampleErrors,
      Function<Span, Boolean> samplerFunc) {
    List<SpanLogs> spanLogsOutput = new ArrayList<>(1);
    try {
      spanLogsDecoder.decode(JSON_PARSER.readTree(message), spanLogsOutput, "dummy");
    } catch (Exception e) {
      handler.reject(message, formatErrorMessage(message, e, ctx));
      return;
    }

    for (SpanLogs spanLogs : spanLogsOutput) {
      String spanMessage = spanLogs.getSpan();
      if (spanMessage == null) {
        // For backwards compatibility, report the span logs if span line data is not included
        handler.report(spanLogs);
      } else {
        ReportableEntityPreprocessor preprocessor = preprocessorSupplier == null ?
            null : preprocessorSupplier.get();
        String[] spanMessageHolder = new String[1];

        // transform the line if needed
        if (preprocessor != null) {
          spanMessage = preprocessor.forPointLine().transform(spanMessage);

          if (!preprocessor.forPointLine().filter(message, spanMessageHolder)) {
            if (spanMessageHolder[0] != null) {
              handler.reject(spanLogs, spanMessageHolder[0]);
            } else {
              handler.block(spanLogs);
            }
            return;
          }
        }
        List<Span> spanOutput = new ArrayList<>(1);
        try {
          spanDecoder.decode(spanMessage, spanOutput, "dummy");
        } catch (Exception e) {
          handler.reject(spanLogs, formatErrorMessage(message, e, ctx));
          return;
        }

        if (!spanOutput.isEmpty()) {
          Span span = spanOutput.get(0);
          if (preprocessor != null) {
            preprocessor.forSpan().transform(span);
            if (!preprocessor.forSpan().filter(span, spanMessageHolder)) {
              if (spanMessageHolder[0] != null) {
                handler.reject(spanLogs, spanMessageHolder[0]);
              } else {
                handler.block(spanLogs);
              }
              return;
            }
          }
          // check whether error span tag exists.
          boolean sampleError = alwaysSampleErrors && span.getAnnotations().stream().anyMatch(t ->
              t.getKey().equals(ERROR_SPAN_TAG_KEY) && t.getValue().equals(ERROR_SPAN_TAG_VAL));
          if (sampleError || samplerFunc.apply(span)) {
            // after sampling, span line data is no longer needed
            spanLogs.setSpan(null);
            handler.report(spanLogs);
          }
        }
      }
    }
  }

  /**
   * Report span and derived metrics if needed.
   *
   * @param object     span.
   */
  protected void report(Span object) {
    handler.report(object);
  }

  protected boolean sample(Span object) {
    return sample(object, discardedSpansBySampler);
  }

  protected boolean sampleSpanLogs(Span object) {
    return sample(object, discardedSpanLogsBySampler);
  }

  private boolean sample(Span object, Counter discarded) {
    if (sampler.sample(object.getName(),
        UUID.fromString(object.getTraceId()).getLeastSignificantBits(), object.getDuration())) {
      return true;
    }
    discarded.inc();
    return false;
  }
}
