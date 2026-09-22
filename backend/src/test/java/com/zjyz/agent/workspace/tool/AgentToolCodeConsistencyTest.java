package com.zjyz.agent.workspace.tool;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具规范名三向一致性守卫：模型函数名、canonical tool code 与 Guard 期望名必须互相可解析。
 * 任何新工具接入若命名不一致，此测试在 CI 直接失败，防止"工具已执行但 Guard 判缺证据"回归。
 */
class AgentToolCodeConsistencyTest {

    private final AgentToolCatalog catalog = new AgentToolCatalog();

    @Test
    void everyDescriptorResolvesThreeWays() {
        for (AgentToolDescriptor descriptor : catalog.descriptors()) {
            String toolCode = descriptor.getToolCode();
            String modelName = descriptor.getModelName();
            assertEquals(toolCode, catalog.canonicalToolCode(modelName),
                    "模型函数名必须解析到 canonical code: " + modelName);
            assertEquals(toolCode, catalog.canonicalToolCode(toolCode),
                    "canonical code 必须自解析: " + toolCode);
            assertEquals(toolCode, catalog.canonicalToolCode(toolCode.replace('.', '_')),
                    "下划线变体必须解析到 canonical code: " + toolCode);
            assertEquals(toolCode, AgentToolCodes.canonicalize(modelName),
                    "AgentToolCodes 必须与 Catalog 对模型函数名的解析一致: " + modelName);
            assertEquals(toolCode, AgentToolCodes.canonicalize(toolCode),
                    "AgentToolCodes 必须保持 canonical code 不变: " + toolCode);
        }
    }

    @Test
    void everyToolCodeConstantHasRegisteredDescriptor() throws IllegalAccessException {
        Set<String> registered = new HashSet<>();
        for (AgentToolDescriptor descriptor : catalog.descriptors()) {
            registered.add(descriptor.getToolCode());
        }
        for (Field field : AgentToolCodes.class.getDeclaredFields()) {
            if (!Modifier.isPublic(field.getModifiers()) || !Modifier.isStatic(field.getModifiers())
                    || field.getType() != String.class) {
                continue;
            }
            String constant = (String) field.get(null);
            assertTrue(registered.contains(constant),
                    "AgentToolCodes 常量必须有对应的已注册工具: " + field.getName() + "=" + constant);
        }
        assertEquals(registered.size(), catalog.descriptors().size(),
                "同一 canonical code 不允许注册多个工具");
    }

    @Test
    void auditedLegacyAliasesResolveToCanonicalCodes() {
        List<String[]> legacyAliases = Arrays.asList(
                new String[]{"finance.receivable_collection.list", AgentToolCodes.FINANCE_RECEIVABLE_COLLECTION_LIST},
                new String[]{"finance.enterprise.kpi", AgentToolCodes.FINANCE_ENTERPRISE_KPI},
                new String[]{"finance.supplier.payable.summary", AgentToolCodes.FINANCE_SUPPLIER_PAYABLE_SUMMARY},
                new String[]{"risk.owner.action.center", AgentToolCodes.RISK_OWNER_ACTION_CENTER});
        for (String[] pair : legacyAliases) {
            assertEquals(pair[1], catalog.canonicalToolCode(pair[0]),
                    "Catalog 必须归一审计发现的旧别名: " + pair[0]);
            assertEquals(pair[1], AgentToolCodes.canonicalize(pair[0]),
                    "AgentToolCodes 必须归一审计发现的旧别名: " + pair[0]);
        }
    }

    @Test
    void canonicalizeToleratesCaseAndWhitespace() {
        assertEquals(AgentToolCodes.FINANCE_RECEIVABLE_COLLECTION_LIST,
                AgentToolCodes.canonicalize("  Finance_Receivable_Collection_List  "));
        assertEquals("unknown.tool", AgentToolCodes.canonicalize("unknown.tool"));
    }
}
