package frontend.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

import org.springframework.stereotype.Component;

@Component
public class MetricsRecorder {

    // Get version from environment
    private final String dashboardVersion;

    // Counter: classifications by result (with labels)
    private final Map<String, AtomicLong> classificationsByResult = new ConcurrentHashMap<>();

    // Gauge: active requests by endpoint (with labels)
    private final Map<String, AtomicInteger> activeRequestsByEndpoint = new ConcurrentHashMap<>();

    // Histogram: latency buckets
    private final double[] bucketBoundaries = {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0};
    private final Map<String, long[]> latencyBucketCounts = new ConcurrentHashMap<>();
    private final Map<String, DoubleAdder> latencySum = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> latencyCount = new ConcurrentHashMap<>();

    // Additional counters
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong modelCalls = new AtomicLong(0);

    public MetricsRecorder() {
        String version = System.getenv("DASHBOARD_VERSION");
        this.dashboardVersion = (version == null || version.isEmpty()) ? "v1" : version;

        // Initialize default endpoint
        activeRequestsByEndpoint.put("/sms", new AtomicInteger(0));
        initHistogram("/sms");
    }

    private void initHistogram(String endpoint) {
        latencyBucketCounts.put(endpoint, new long[bucketBoundaries.length + 1]); // +1 for +Inf
        latencySum.put(endpoint, new DoubleAdder());
        latencyCount.put(endpoint, new AtomicLong(0));
    }

    // === Recording methods ===

    public long startTimer() {
        return System.nanoTime();
    }

    public void recordClassification(String result, long startNanos) {
        String safeResult = (result == null) ? "unknown" : result;
        classificationsByResult.computeIfAbsent(safeResult, k -> new AtomicLong(0)).incrementAndGet();

        // Record latency
        double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        recordLatency("/sms", seconds);
    }

    private synchronized void recordLatency(String endpoint, double seconds) {
        long[] buckets = latencyBucketCounts.get(endpoint);
        if (buckets == null) return;

        // Increment appropriate buckets (cumulative)
        for (int i = 0; i < bucketBoundaries.length; i++) {
            if (seconds <= bucketBoundaries[i]) {
                buckets[i]++;
            }
        }
        buckets[bucketBoundaries.length]++; // +Inf always incremented

        latencySum.get(endpoint).add(seconds);
        latencyCount.get(endpoint).incrementAndGet();
    }

    public void incrementInFlight() {
        activeRequestsByEndpoint.computeIfAbsent("/sms", k -> new AtomicInteger(0)).incrementAndGet();
    }

    public void decrementInFlight() {
        AtomicInteger counter = activeRequestsByEndpoint.get("/sms");
        if (counter != null) counter.decrementAndGet();
    }

    public void recordCacheHit() {
        cacheHits.incrementAndGet();
    }

    public void recordCacheMiss() {
        cacheMisses.incrementAndGet();
    }

    public void recordModelCall() {
        modelCalls.incrementAndGet();
    }

    // === Prometheus Output (MANUAL - not using any library!) ===

    public String getPrometheusOutput() {
        StringBuilder sb = new StringBuilder();

        // --- Counter: sms_messages_classified_total (with labels) ---
        sb.append("# HELP sms_messages_classified_total Total SMS messages classified\n");
        sb.append("# TYPE sms_messages_classified_total counter\n");
        for (Map.Entry<String, AtomicLong> entry : classificationsByResult.entrySet()) {
            sb.append(String.format(
                "sms_messages_classified_total{result=\"%s\",source=\"web\",dashboard_version=\"%s\"} %d\n",
                entry.getKey(), dashboardVersion, entry.getValue().get()));
        }

        // --- Gauge: sms_active_requests (with labels) ---
        sb.append("# HELP sms_active_requests Current number of active requests\n");
        sb.append("# TYPE sms_active_requests gauge\n");
        for (Map.Entry<String, AtomicInteger> entry : activeRequestsByEndpoint.entrySet()) {
            sb.append(String.format(
                "sms_active_requests{endpoint=\"%s\",dashboard_version=\"%s\"} %d\n",
                entry.getKey(), dashboardVersion, entry.getValue().get()));
        }

        // --- Histogram: sms_request_latency_seconds (with labels) ---
        sb.append("# HELP sms_request_latency_seconds Latency for SMS classification requests\n");
        sb.append("# TYPE sms_request_latency_seconds histogram\n");
        for (String endpoint : latencyBucketCounts.keySet()) {
            long[] buckets = latencyBucketCounts.get(endpoint);
            long cumulative = 0;

            for (int i = 0; i < bucketBoundaries.length; i++) {
                cumulative += buckets[i];
                sb.append(String.format(
                    "sms_request_latency_seconds_bucket{endpoint=\"%s\",dashboard_version=\"%s\",le=\"%.3f\"} %d\n",
                    endpoint, dashboardVersion, bucketBoundaries[i], cumulative));
            }
            // +Inf bucket
            cumulative += buckets[bucketBoundaries.length];
            sb.append(String.format(
                "sms_request_latency_seconds_bucket{endpoint=\"%s\",dashboard_version=\"%s\",le=\"+Inf\"} %d\n",
                endpoint, dashboardVersion, cumulative));

            sb.append(String.format(
                "sms_request_latency_seconds_sum{endpoint=\"%s\",dashboard_version=\"%s\"} %.6f\n",
                endpoint, dashboardVersion, latencySum.get(endpoint).sum()));
            sb.append(String.format(
                "sms_request_latency_seconds_count{endpoint=\"%s\",dashboard_version=\"%s\"} %d\n",
                endpoint, dashboardVersion, latencyCount.get(endpoint).get()));
        }

        // --- Counter: sms_cache_hits_total ---
        sb.append("# HELP sms_cache_hits_total Number of cache hits\n");
        sb.append("# TYPE sms_cache_hits_total counter\n");
        sb.append(String.format("sms_cache_hits_total{dashboard_version=\"%s\"} %d\n",
            dashboardVersion, cacheHits.get()));

        // --- Counter: sms_cache_misses_total ---
        sb.append("# HELP sms_cache_misses_total Number of cache misses\n");
        sb.append("# TYPE sms_cache_misses_total counter\n");
        sb.append(String.format("sms_cache_misses_total{dashboard_version=\"%s\"} %d\n",
            dashboardVersion, cacheMisses.get()));

        // --- Counter: sms_model_calls_total ---
        sb.append("# HELP sms_model_calls_total Number of calls to model service\n");
        sb.append("# TYPE sms_model_calls_total counter\n");
        sb.append(String.format("sms_model_calls_total{dashboard_version=\"%s\"} %d\n",
            dashboardVersion, modelCalls.get()));

        return sb.toString();
    }
}