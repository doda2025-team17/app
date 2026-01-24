package frontend.ctrl;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import frontend.metrics.MetricsRecorder;

@RestController
public class MetricsController {

    private final MetricsRecorder metrics;

    public MetricsController(MetricsRecorder metrics) {
        this.metrics = metrics;
    }

    @GetMapping(value = "/metrics", produces = "text/plain; charset=utf-8")
    public String metrics() {
        return metrics.getPrometheusOutput();
    }
}