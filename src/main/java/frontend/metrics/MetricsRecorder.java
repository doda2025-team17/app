package frontend.metrics;

import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Counter;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
public class MetricsRecorder {

    private final MeterRegistry registry;
    private final Timer classificationTimer;
    private final AtomicInteger inFlight;
    private final Counter cacheHits;
    private final Counter cacheMisses;
    private final Counter modelCalls;


    public MetricsRecorder(MeterRegistry registry) {
        this.registry = registry;
        
        String dashboardVersion = System.getenv("DASHBOARD_VERSION");
        if (dashboardVersion == null || dashboardVersion.isEmpty()) {
            dashboardVersion = "v1";  // Default value
        }
        
        registry.config().commonTags("dashboard_version", dashboardVersion);

        this.cacheHits = Counter.builder("sms_cache_hits_total")
                .description("Number of cache hits for SMS classification")
                .register(registry);

        this.cacheMisses = Counter.builder("sms_cache_misses_total")
                .description("Number of cache misses for SMS classification")
                .register(registry);

        this.modelCalls = Counter.builder("sms_model_calls_total")
                .description("Number of calls from frontend to model-service")
                .register(registry);


        this.classificationTimer = Timer.builder("sms_request_latency_seconds")
                .description("Latency for SMS classification requests")
                .publishPercentileHistogram()
                .tags("endpoint", "/sms")
                .register(registry);

        this.inFlight = registry.gauge("sms_active_requests", new AtomicInteger(0));
    }

    public Timer.Sample startTimer() {
        return Timer.start();
    }

    public void recordClassification(String result, Timer.Sample sample) {
        var safeResult = result == null ? "unknown" : result;
        registry.counter("sms_messages_classified_total",
                "source", "web",
                "result", safeResult).increment();
        sample.stop(classificationTimer);
    }

    public void incrementInFlight() {
        if (inFlight != null) {
            inFlight.incrementAndGet();
        }
    }

    public void decrementInFlight() {
        if (inFlight != null) {
            inFlight.decrementAndGet();
        }
    }

    public void recordCacheHit() {
        cacheHits.increment();
    }

    public void recordCacheMiss() {
        cacheMisses.increment();
    }

    public void recordModelCall() {
        modelCalls.increment();
    }
}
