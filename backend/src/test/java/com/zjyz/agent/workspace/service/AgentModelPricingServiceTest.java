package com.zjyz.agent.workspace.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentModelPricingServiceTest {
    @Test
    void exactModelOverrideUsesDecimalArithmetic() {
        AgentModelPricingService service = new AgentModelPricingService();
        ReflectionTestUtils.setField(service, "defaultInputPrice", new BigDecimal("10"));
        ReflectionTestUtils.setField(service, "defaultOutputPrice", new BigDecimal("30"));
        ReflectionTestUtils.setField(service, "overrides", "glm/model-x:2.50:8.00;glm/*:3:9");

        assertEquals(new BigDecimal("0.00010500"), service.estimate("glm", "model-x", 10, 10));
        assertEquals(new BigDecimal("0.00012000"), service.estimate("glm", "other", 10, 10));
    }
}
