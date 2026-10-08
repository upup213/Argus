package org.example.agent.tool;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class QueryMetricsToolsTest {

    private final QueryMetricsTools tools = new QueryMetricsTools();

    @Test
    void calculateDuration_moreThanOneHour_formatsHms() {
        String activeAt = Instant.now().minus(2, ChronoUnit.HOURS).toString();
        assertThat(tools.calculateDuration(activeAt)).isEqualTo("2h0m0s");
    }

    @Test
    void calculateDuration_moreThanOneMinute_formatsMs() {
        String activeAt = Instant.now().minus(5, ChronoUnit.MINUTES).toString();
        assertThat(tools.calculateDuration(activeAt)).isEqualTo("5m0s");
    }

    @Test
    void calculateDuration_lessThanOneMinute_formatsSeconds() {
        String activeAt = Instant.now().minus(30, ChronoUnit.SECONDS).toString();
        assertThat(tools.calculateDuration(activeAt)).isEqualTo("30s");
    }

    @Test
    void calculateDuration_invalidInput_returnsUnknown() {
        assertThat(tools.calculateDuration("not-a-valid-time")).isEqualTo("unknown");
    }
}
