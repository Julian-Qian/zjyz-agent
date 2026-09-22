package com.zjyz.agent.workspace.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Uses operator-provided reference prices; defaults are conservative placeholders, not live vendor quotes. */
@Component
public class AgentModelPricingService {
    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");

    @Value("${agent.runtime.pricing.default-input-per-million-cny:10.00}")
    private BigDecimal defaultInputPrice;

    @Value("${agent.runtime.pricing.default-output-per-million-cny:30.00}")
    private BigDecimal defaultOutputPrice;

    /** Format: provider/model:inputPrice:outputPrice;provider/*:inputPrice:outputPrice */
    @Value("${agent.runtime.pricing.overrides:}")
    private String overrides;

    public BigDecimal estimate(String provider, String model, int promptTokens, int completionTokens) {
        Rate rate = rates().get(key(provider, model));
        if (rate == null) {
            rate = rates().get(key(provider, "*"));
        }
        if (rate == null) {
            rate = new Rate(nonNegative(defaultInputPrice), nonNegative(defaultOutputPrice));
        }
        BigDecimal input = BigDecimal.valueOf(Math.max(0, promptTokens)).multiply(rate.input)
                .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
        BigDecimal output = BigDecimal.valueOf(Math.max(0, completionTokens)).multiply(rate.output)
                .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
        return input.add(output).setScale(8, RoundingMode.HALF_UP);
    }

    private Map<String, Rate> rates() {
        Map<String, Rate> result = new HashMap<>();
        if (!StringUtils.hasText(overrides)) {
            return result;
        }
        for (String item : overrides.split(";")) {
            String[] parts = item.trim().split(":");
            if (parts.length != 3 || !parts[0].contains("/")) {
                continue;
            }
            try {
                result.put(parts[0].trim().toLowerCase(Locale.ROOT),
                        new Rate(nonNegative(new BigDecimal(parts[1].trim())),
                                nonNegative(new BigDecimal(parts[2].trim()))));
            } catch (NumberFormatException ignored) {
                // Invalid override is ignored; the conservative default remains effective.
            }
        }
        return result;
    }

    private String key(String provider, String model) {
        return String.valueOf(provider).trim().toLowerCase(Locale.ROOT) + "/"
                + String.valueOf(model).trim().toLowerCase(Locale.ROOT);
    }

    private BigDecimal nonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
    }

    private static final class Rate {
        private final BigDecimal input;
        private final BigDecimal output;

        private Rate(BigDecimal input, BigDecimal output) {
            this.input = input;
            this.output = output;
        }
    }
}
