package frontend.ctrl;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import frontend.data.Sms;
import frontend.metrics.MetricsRecorder;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;

@Controller
@RequestMapping(path = "/sms")
public class FrontendController {

    private String modelHost;

    private RestTemplateBuilder rest;

    private MetricsRecorder metrics;

    private static class CacheEntry {
        final String result;
        final long expiresAtMillis;
        CacheEntry(String result, long expiresAtMillis) {
            this.result = result;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final long cacheTtlMillis = Duration.ofMinutes(5).toMillis();

    public FrontendController(RestTemplateBuilder rest, Environment env, MetricsRecorder metrics) {
        this.rest = rest;
        this.metrics = metrics;
        this.modelHost = env.getProperty("MODEL_HOST");
        assertModelHost();
    }

    private void assertModelHost() {
        if (modelHost == null || modelHost.strip().isEmpty()) {
            System.err.println("ERROR: ENV variable MODEL_HOST is null or empty");
            System.exit(1);
        }
        modelHost = modelHost.strip();
        if (modelHost.indexOf("://") == -1) {
            var m = "ERROR: ENV variable MODEL_HOST is missing protocol, like \"http://...\" (was: \"%s\")\n";
            System.err.printf(m, modelHost);
            System.exit(1);
        } else {
            System.out.printf("Working with MODEL_HOST=\"%s\"\n", modelHost);
        }
    }

    @GetMapping("")
    public String redirectToSlash(HttpServletRequest request) {
        // relative REST requests in JS will end up on / and not on /sms
        return "redirect:" + request.getRequestURI() + "/";
    }

    @GetMapping("/")
    public String index(Model m) {
        m.addAttribute("hostname", modelHost);
        m.addAttribute("dashboardVersion", System.getenv().getOrDefault("DASHBOARD_VERSION", "v1"));
        return "sms/index";
    }


    @PostMapping({ "", "/" })
    @ResponseBody
    public ResponseEntity<Sms> predict(@RequestBody Sms sms) {
        metrics.incrementInFlight();
        Timer.Sample sample = metrics.startTimer();

        String dashV = System.getenv().getOrDefault("DASHBOARD_VERSION", "v1");

        boolean cacheHit = false;

        try {
            String key = sms.sms == null ? "" : sms.sms.trim();
            long now = System.currentTimeMillis();

            CacheEntry cached = cache.get(key);
            if (cached != null && cached.expiresAtMillis > now) {
                cacheHit = true;
                metrics.recordCacheHit();
                sms.result = cached.result;   // use cached result
            } else {
                metrics.recordCacheMiss();
                metrics.recordModelCall();
                sms.result = getPrediction(sms);
                cache.put(key, new CacheEntry(sms.result, now + cacheTtlMillis));
            }

            metrics.recordClassification(sms.result, sample);

            HttpHeaders h = new HttpHeaders();
            h.add("X-App-Version", dashV);
            h.add("X-Cache", cacheHit ? "HIT" : "MISS");

            return new ResponseEntity<>(sms, h, HttpStatus.OK);
        } finally {
            metrics.decrementInFlight();
        }
    }

    private String getPrediction(Sms sms) {
        try {
            var url = new URI(modelHost + "/predict");
            var c = rest.build().postForEntity(url, sms, Sms.class);
            return c.getBody().result.trim();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
