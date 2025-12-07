package frontend.metrics;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

@Component
public class MetricsRecorder {

    private final MeterRegistry registry;
    private final Timer classificationTimer;
    private final AtomicInteger inFlight;

    public MetricsRecorder(MeterRegistry registry) {
        this.registry = registry;
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
}
