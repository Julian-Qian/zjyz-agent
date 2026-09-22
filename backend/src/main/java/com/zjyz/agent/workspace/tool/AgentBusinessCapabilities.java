package com.zjyz.agent.workspace.tool;

import java.util.*;

/** Reviewed service bindings. No endpoint, SQL or Java method can be supplied by a model. */
public final class AgentBusinessCapabilities {
    private AgentBusinessCapabilities() {}
    private static final Map<String, Definition> ALL = new LinkedHashMap<>();
    static {
        add("project.reconciliation_schedule", "合同周期财务对账计划", "FROZEN_SELECTION", "根据首张租出/租入业务日期和合同月周期，查询本月或未来财务应对账计划；规则缺失不等于无需对账。不支持历史合同状态重建。", "targetMonth reconciliationType:FINANCE|MATERIAL");
        add("finance.material_settled_rent", "已结算材料租金排行", "FROZEN_SELECTION", "按正式结算账期结束日归属统计已结算材料基础租金加递增租金的金额排行；分别核验全部有效结算分段与快照基础/递增金额，缺数据拒绝全量排名。不是利润、回款、未结算租金。", "startDate endDate");
        add("market.search", "商城找材料", "PUBLIC_MARKET", "商城找材料；使用已发布的公开商城数据，独立于项目选择。联系方式仅展示，不代表已联系或下单。按规格、城市和最低可供量筛选；PRICE_ASC要求同时提供计数单位和报价单位，按全部匹配的已报价材料排序，不得跨单位比较；未报价项不参与价格排序。", "keyword city categoryName stationCid materialSpecification countingUnit marketPriceUnit minAvailableQty:number sortField:RELEVANCE|PRICE_ASC pageNum:integer pageSize:integer");
        add("market.cities", "商城可选城市", "PUBLIC_MARKET", "商城可选城市；使用已发布的公开商城数据，独立于项目选择。联系方式仅展示，不代表已联系或下单。", "");
        add("market.detail", "商城材料详情", "PUBLIC_MARKET", "商城材料详情；使用已发布的公开商城数据，独立于项目选择。联系方式仅展示，不代表已联系或下单。", "stationCid! mid!");
        add("market.contact", "站点公开联系方式", "PUBLIC_MARKET", "站点公开联系方式；使用已发布的公开商城数据，独立于项目选择。联系方式仅展示，不代表已联系或下单。", "stationCid!");
        add("inventory.materials", "库存材料清单", "TENANT_INVENTORY", "库存材料清单；使用当前企业数据，独立于项目选择。", "keyword materialCategory anomalyOnly:boolean sortField sortOrder:asc|desc pageNum:integer pageSize:integer");
        add("inventory.material_detail", "材料库存明细", "TENANT_INVENTORY", "材料库存明细；使用当前企业数据，独立于项目选择。", "materialId!");
        add("inventory.material_ledger", "材料库存流水", "TENANT_INVENTORY", "材料库存流水；使用当前企业数据，独立于项目选择。", "materialId! startDate endDate behaviorTypes pageNum:integer pageSize:integer");
        add("inventory.material_trend", "材料库存趋势", "TENANT_INVENTORY", "材料库存趋势；使用当前企业数据，独立于项目选择。", "materialId! startDate endDate");
        add("inventory.workbench", "库存工作台", "TENANT_INVENTORY", "库存工作台；使用当前企业数据，独立于项目选择。", "startDate endDate");
        add("inventory.count_tasks", "仓库盘点任务", "TENANT_INVENTORY", "仓库盘点任务；使用当前企业数据，独立于项目选择。", "status keyword pageNum:integer pageSize:integer");
        add("inventory.count_detail", "仓库盘点详情", "TENANT_INVENTORY", "仓库盘点详情；使用当前企业数据，独立于项目选择。", "taskId!");
        add("inventory.site_distribution", "企业工地材料分布", "TENANT_INVENTORY", "企业工地材料分布；使用当前企业数据，独立于项目选择。", "date keyword anomalyOnly:boolean sortField sortOrder:asc|desc pageNum:integer pageSize:integer");
        add("project.detail", "项目详情", "PROJECT_SELECTION", "项目详情；必须在冻结项目范围内，先用项目清单解析名称得到真实projectId。", "projectId!");
        add("project.progress", "项目材料租还进度", "PROJECT_SELECTION", "项目材料租还进度；必须在冻结项目范围内，先用项目清单解析名称得到真实projectId。", "projectId! direction:RENT_OUT|RETURN|RENT_IN|RENT_IN_RETURN!");
        add("project.site_materials", "项目工地材料", "PROJECT_SELECTION", "项目工地材料；必须在冻结项目范围内，先用项目清单解析名称得到真实projectId。", "projectId! date keyword");
        add("project.site_ledger", "项目工地材料流水", "PROJECT_SELECTION", "项目工地材料流水；必须在冻结项目范围内，先用项目清单解析名称得到真实projectId。", "projectId! materialId! startDate endDate");
        add("project.site_count_tasks", "项目工地盘点任务", "PROJECT_SELECTION", "项目工地盘点任务；必须在冻结项目范围内，先用项目清单解析名称得到真实projectId。", "projectId! status pageNum:integer pageSize:integer");
        add("contract.detail", "项目合同详情", "PROJECT_SELECTION", "项目合同详情；必须在冻结项目范围内，先用项目清单解析名称得到真实projectId。", "projectId!");
        add("contract.material_categories", "合同材料分类", "PROJECT_SELECTION", "合同材料分类；必须在冻结项目范围内，先用项目清单解析名称得到真实projectId。", "projectId!");
        add("contract.material_prices", "合同材料价格", "PROJECT_SELECTION", "合同材料价格；必须在冻结项目范围内，先用项目清单解析名称得到真实projectId。", "projectId! categoryName! materialBusinessType");
        add("contract.price_ladders", "合同材料阶梯价", "PROJECT_SELECTION", "合同材料阶梯价；必须在冻结项目范围内，先用项目清单解析名称得到真实projectId。", "projectId! mid!");
        add("document.list_records", "项目单据列表", "PROJECT_SELECTION", "项目单据列表；必须在冻结项目范围内，先用项目清单解析名称得到真实projectId。", "projectId! documentType:RENT_OUT|RETURN|COMPENSATION|RENT_IN|RENT_IN_RETURN! keyword pageNum:integer pageSize:integer");
        add("document.detail", "项目单据详情", "PROJECT_SELECTION", "项目单据详情；必须在冻结项目范围内，先用项目清单解析名称得到真实projectId。", "projectId! documentType:RENT_OUT|RETURN|COMPENSATION|RENT_IN|RENT_IN_RETURN! documentId!");
        add("inventory.documents", "仓库单据列表", "TENANT_INVENTORY", "仓库单据列表；使用当前企业数据，独立于项目选择。", "documentType:PROCUREMENT|INBOUND|OUTBOUND|TEMPORARY_STORAGE|TEMPORARY_RETURN! keyword pageNum:integer pageSize:integer");
        add("master.materials", "企业材料库", "TENANT_MASTER", "企业材料库；使用当前企业数据，独立于项目选择。", "keyword materialName categoryName materialBusinessType pageNum:integer pageSize:integer");
        add("master.categories", "材料分类", "TENANT_MASTER", "材料分类；使用当前企业数据，独立于项目选择。", "");
        add("master.units", "材料计数单位", "TENANT_MASTER", "材料计数单位；使用当前企业数据，独立于项目选择。", "");
        add("master.pricing_units", "材料计价单位", "TENANT_MASTER", "材料计价单位；使用当前企业数据，独立于项目选择。", "");
        add("master.personnel", "人员资料", "TENANT_MASTER", "人员资料；使用当前企业数据，独立于项目选择。", "keyword pageNum:integer pageSize:integer");
        add("master.transport", "运输人员与车辆", "TENANT_MASTER", "运输人员与车辆；使用当前企业数据，独立于项目选择。", "keyword pageNum:integer pageSize:integer");
        add("estimate.list", "材料预估记录", "TENANT_MASTER", "材料预估记录；使用当前企业数据，独立于项目选择。", "keyword suggestion pageNum:integer pageSize:integer");
        add("estimate.detail", "材料预估详情", "TENANT_MASTER", "材料预估详情；使用当前企业数据，独立于项目选择。", "estimateId!");
        add("estimate.match", "预估材料库存匹配", "TENANT_MASTER", "预估材料库存匹配；使用当前企业数据，独立于项目选择。", "estimateId!");
        add("estimate.supported_scope", "材料预估支持范围", "TENANT_MASTER", "材料预估支持范围；使用当前企业数据，独立于项目选择。", "");
        add("finance.customer_payments", "客户登记收款", "PROJECT_SELECTION", "客户登记收款；必须在冻结项目范围内，先用项目清单解析名称得到真实projectId。登记收付款不代表利润或银行到账。", "projectId! keyword pageNum:integer pageSize:integer");
        add("finance.supplier_payments", "供应商登记付款", "PROJECT_SELECTION", "供应商登记付款；必须在冻结项目范围内，先用项目清单解析名称得到真实projectId。登记收付款不代表利润或银行到账。", "projectId! keyword pageNum:integer pageSize:integer");
        add("finance.settlement_periods", "项目结算账期", "PROJECT_SELECTION", "项目结算账期；必须在冻结项目范围内，先用项目清单解析名称得到真实projectId。登记收付款不代表利润或银行到账。", "projectId!");
        add("finance.settlement_records", "项目财务对账单", "PROJECT_SELECTION", "项目财务对账单；必须在冻结项目范围内，先用项目清单解析名称得到真实projectId。登记收付款不代表利润或银行到账。", "projectId! keyword pageNum:integer pageSize:integer");
        add("project.reports", "项目经营报告记录", "PROJECT_SELECTION", "项目经营报告记录；必须在冻结项目范围内，先用项目清单解析名称得到真实projectId。", "projectId! pageNum:integer pageSize:integer");
        add("master.billing_units","开票单位","TENANT_MASTER","开票单位；不依赖项目选择，使用当前企业或已发布的公开商城信息。","keyword pageNum:integer pageSize:integer");
        add("market.estimate_shortage","预估缺口商城推荐","PUBLIC_MARKET","预估缺口商城推荐；不依赖项目选择，使用当前企业或已发布的公开商城信息。读取本企业已有预估缺口并推荐公开材料；不会联系站点、下单或修改库存；仅返回推荐结果。","estimateId! topN:integer");
        add("inventory.document_detail","仓库单据详情","TENANT_INVENTORY","仓库单据详情；不依赖项目选择，使用当前企业或已发布的公开商城信息。","documentType:PROCUREMENT|INBOUND|OUTBOUND|TEMPORARY_STORAGE|TEMPORARY_RETURN! documentId!");
        add("inventory.temporary_open","未退完的暂存单","TENANT_INVENTORY","未退完的暂存单；不依赖项目选择，使用当前企业或已发布的公开商城信息。","");
        add("inventory.temporary_returnable","暂存可退材料","TENANT_INVENTORY","暂存可退材料；不依赖项目选择，使用当前企业或已发布的公开商城信息。","documentId!");
        add("project.site_count_detail","工地盘点详情","PROJECT_SELECTION","工地盘点详情；限当前选中项目，先解析真实ID。","projectId! taskId!");
        add("document.review_history","单据复核记录","PROJECT_SELECTION","单据复核记录；限当前选中项目，先解析真实ID。","projectId! documentType:RENT_OUT|RETURN|COMPENSATION|RENT_IN|RENT_IN_RETURN! documentId!");
        add("project.report_detail","项目经营报告详情","PROJECT_SELECTION","项目经营报告详情；限当前选中项目，先解析真实ID。","projectId! reportId!");
        add("finance.customer_payment_detail","客户收款详情","PROJECT_SELECTION","客户收款详情；限当前选中项目，先解析真实ID。","projectId! paymentId!");
        add("finance.supplier_payment_detail","供应商付款详情","PROJECT_SELECTION","供应商付款详情；限当前选中项目，先解析真实ID。","projectId! paymentId!");
        add("finance.settlement_detail","财务对账单详情","PROJECT_SELECTION","财务对账单详情；限当前选中项目，先解析真实ID。","projectId! documentId!");
        add("project.reconciliation_records","物料对账单列表","PROJECT_SELECTION","物料对账单列表；限当前选中项目，先解析真实ID。","projectId! keyword pageNum:integer pageSize:integer");
        add("project.reconciliation_detail","物料对账单详情","PROJECT_SELECTION","物料对账单详情；限当前选中项目，先解析真实ID。","projectId! documentId!");
        add("contract.misc_fees","合同材料杂费","PROJECT_SELECTION","合同材料杂费；限当前选中项目，先解析真实ID。","projectId! documentType:RENT_OUT|RETURN|COMPENSATION|RENT_IN|RENT_IN_RETURN!");
        add("navigation.project_workflow","打开项目业务页面","PROJECT_SELECTION","在选中的项目打开合同、项目材料、租还单据、物料对账或财务页面；只准备操作入口，不代表保存或复核完成。","projectId! workflow:overview|contract|projectMaterial|rentOut|returnOrder|compensationOrder|rentIn|rentInReturn|materialReconciliation|customerPayment|financeCheck!");
        add("navigation.open","打开业务功能","TENANT_MASTER","打开业务功能；不依赖项目选择，使用当前企业或已发布的公开商城信息。只生成白名单页面入口，不代表已保存、复核、付款或完成业务操作。","target:PROJECT_RENT_OUT|PROJECT_RENT_IN|INVENTORY|PROCUREMENT|INBOUND|OUTBOUND|WAREHOUSE_COUNT|TEMPORARY_STORAGE|TEMPORARY_RETURN|SITE_DISTRIBUTION|MATERIALS|PERSONNEL|BILLING_UNITS|ESTIMATE|MARKET|HELP!");
        add("document.export","导出项目单据Excel","PROJECT_SELECTION","导出项目单据Excel；限当前选中项目，先解析真实ID。","projectId! documentType:RENT_OUT|RETURN|COMPENSATION|RENT_IN|RENT_IN_RETURN! documentId!");
        add("estimate.export","导出预估Excel","TENANT_MASTER","导出预估Excel；不依赖项目选择，使用当前企业或已发布的公开商城信息。","estimateId!");

    }
    public static Collection<Definition> definitions() { return Collections.unmodifiableCollection(ALL.values()); }
    public static Definition find(String code) { return ALL.get(code); }
    private static void add(String code, String title, String scope, String description, String fields) {
        ALL.put(code, new Definition(code,title,scope,description,fields));
    }
    public static final class Definition {
        public final String code, title, scope, description;
        public final Map<String,Object> parameters = new LinkedHashMap<>();
        public final Map<String,Map<String,Object>> properties = new LinkedHashMap<>();
        public final List<String> required = new ArrayList<>();
        Definition(String code,String title,String scope,String description,String fields) {
            this.code=code; this.title=title; this.scope=scope; this.description=description;
            for (String token : fields.split(" ")) {
                if (token.isEmpty()) continue;
                boolean mandatory=token.endsWith("!");
                String[] parts=token.replace("!", "").split(":",2);
                String kind=parts.length==1 ? "string" : parts[1];
                Map<String,Object> property=new LinkedHashMap<>();
                property.put("type",kind.contains("|") ? "string" : kind);
                if (kind.contains("|")) property.put("enum",Arrays.asList(kind.split("\\|")));
                if ("pageNum".equals(parts[0])) { property.put("minimum",1); property.put("maximum",10000); property.put("default",1); }
                if ("pageSize".equals(parts[0])) { property.put("minimum",1); property.put("maximum",50); property.put("default",20); }
                if ("topN".equals(parts[0])) { property.put("minimum",1); property.put("maximum",20); }
                if ("number".equals(kind)) property.put("minimum",0);
                if ("string".equals(property.get("type"))) property.put("maxLength",200);
                properties.put(parts[0],property);
                if (mandatory) required.add(parts[0]);
            }
            parameters.put("type","object"); parameters.put("properties",properties);
            parameters.put("required",required); parameters.put("additionalProperties",false);
        }
        public String modelName() { return code.replace('.','_'); }
        public String scopeBehavior() { return ("PROJECT_SELECTION".equals(scope) || "FROZEN_SELECTION".equals(scope))
            ? AgentToolDescriptor.SCOPE_RESPECTS_SELECTION : AgentToolDescriptor.SCOPE_IGNORES_SELECTION; }
    }
}
