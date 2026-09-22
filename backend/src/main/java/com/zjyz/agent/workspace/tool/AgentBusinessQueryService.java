package com.zjyz.agent.workspace.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.model.AgentEvidence;
import com.zjyz.agent.orch.AgentSkillExecution;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.common.util.CommonUtil;
import com.zjyz.pojo.param.req.*;
import com.zjyz.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.*;

@Service
public class AgentBusinessQueryService {
    @Autowired private ObjectMapper json;
    @Autowired private BillingUnitService billingUnits;
    @Autowired private TemporaryInventoryDocumentService temporary;
    @Autowired private ProcurementDocumentService procurement;
    @Autowired private InboundDocumentService inbound;
    @Autowired private OutboundDocumentService outbound;
    @Autowired private DocumentReviewAuditService reviewAudit;
    @Autowired private ReconciliationDocumentService reconciliation;
    @Autowired private com.zjyz.dao.SettlementDocumentMapper settlementMapper;
    @Autowired private com.zjyz.dao.ReconciliationDocumentMapper reconciliationMapper;

    @Autowired private MarketService market;
    @Autowired private InventoryService inventory;
    @Autowired private ProjectService projects;
    @Autowired private ContractService contracts;
    @Autowired private ProjectMaterialService pricing;
    @Autowired private DocumentStrategyFactory documentStrategies;
    @Autowired private RentDocumentService rent;
    @Autowired private ReturnDocumentService returns;
    @Autowired private CompensationDocumentService compensation;
    @Autowired private RentInDocumentService rentIn;
    @Autowired private RentInReturnDocumentService rentInReturn;
    @Autowired private MaterialService materials;
    @Autowired private PersonnelInfoService personnel;
    @Autowired private TransportInfoService transport;
    @Autowired private MaterialEstimateService estimates;
    @Autowired private CustomerPaymentService customers;
    @Autowired private SupplierPaymentService suppliers;
    @Autowired private SettlementDocumentService settlements;
    @Autowired private ProjectReportService reports;

    public AgentSkillExecution execute(String code, String arguments, AgentRuntimeRecords.Workspace w) {
        AgentBusinessCapabilities.Definition d=AgentBusinessCapabilities.find(code);
        if (d==null) throw new MyBizException("未注册的业务能力","AGT400");
        if (w==null || !StringUtils.hasText(w.getCid()) || !w.getCid().equals(CommonUtil.getCid()))
            throw new MyBizException("企业访问范围不一致","AGT403");
        if (code.startsWith("finance.") && !Boolean.TRUE.equals(w.getFinanceEnabled()))
            throw new MyBizException("未开通财务查询权限","AGT403");
        Map<String,Object> a=parse(arguments);
        validate(d,a);
        List<String> missing=new ArrayList<>();
        for (String name:d.required) if (!StringUtils.hasText(str(a,name))) missing.add(label(name));
        if (!missing.isEmpty()) return AgentSkillExecution.clarification("BUSINESS_QUERY",
            "请补充"+String.join("、",missing)+"，我就可以继续查询。",missing,null);
        if ("PROJECT_SELECTION".equals(d.scope)) project(a,w);
        Object data;
        switch(code) {
            case "market.search": data=market.queryMaterialList(dto(a, QueryMarketMaterialListParam.class)); break;
            case "market.cities": data=market.queryCityOptions(); break;
            case "market.detail": data=market.queryMaterialDetail(str(a,"stationCid"),str(a,"mid")); break;
            case "market.contact": data=market.queryStationContact(str(a,"stationCid")); break;
            case "inventory.materials": data=inventory.queryInventoryMaterials(dto(a, QueryInventoryMaterialsParam.class)); break;
            case "inventory.material_detail": data=inventory.queryInventoryDetail(str(a,"materialId")); break;
            case "inventory.material_ledger": data=inventory.queryInventoryLedger(dto(a, QueryInventoryLedgerParam.class)); break;
            case "inventory.material_trend": data=inventory.queryInventoryMaterialTrend(str(a,"materialId"),str(a,"startDate"),str(a,"endDate")); break;
            case "inventory.workbench": data=inventory.queryInventoryWorkbench(str(a,"startDate"),str(a,"endDate"),20,20); break;
            case "inventory.count_tasks": data=inventory.queryReconciliationTaskList(num(a,"pageNum"),num(a,"pageSize"),str(a,"status"),str(a,"keyword")); break;
            case "inventory.count_detail": data=inventory.queryReconciliationTaskDetail(str(a,"taskId")); break;
            case "inventory.site_distribution": data=inventory.querySiteDistributionDashboard(dto(a, QuerySiteDistributionParam.class)); break;
            case "project.detail": data=projects.queryProjectInfo(project(a,w)); break;
            case "project.progress": data=progress(a,w); break;
            case "project.site_materials": data=inventory.querySiteDistributionDetail(project(a,w),str(a,"date"),str(a,"keyword")); break;
            case "project.site_ledger": data=inventory.querySiteDistributionLedger(project(a,w),str(a,"materialId"),str(a,"startDate"),str(a,"endDate")); break;
            case "project.site_count_tasks": data=inventory.querySiteInventoryCheckTaskList(num(a,"pageNum"),num(a,"pageSize"),project(a,w),str(a,"status")); break;
            case "contract.detail": data=contracts.queryContract(project(a,w)); break;
            case "contract.material_categories": data=pricing.queryProjectMaterialCategory(project(a,w)); break;
            case "contract.material_prices": data=pricing.queryCategoryMaterialPricing(project(a,w),str(a,"categoryName"),str(a,"materialBusinessType")); break;
            case "contract.price_ladders": data=pricing.queryMaterialPriceLadders(project(a,w),str(a,"mid")); break;
            case "document.list_records": data=documents(a,w); break;
            case "document.detail": data=document(a,w); break;
            case "inventory.documents": data=warehouseDocuments(a); break;
            case "master.materials": data=materials.queryMaterialInfo(dto(a,QueryMaterialInfoParam.class)); break;
            case "master.categories": data=materials.getMaterialCategory(); break;
            case "master.units": data=materials.getMaterialUnit(); break;
            case "master.pricing_units": data=materials.getPricingUnit(); break;
            case "master.personnel": data=personnel.queryPage(dto(a,PersonnelInfoRequest.class)); break;
            case "master.transport": data=transport.queryPage(dto(a,TransportInfoRequest.class)); break;
            case "estimate.list": data=estimates.queryEstimateList(dto(a,QueryMaterialEstimateListParam.class)); break;
            case "estimate.detail": data=estimates.queryEstimateDetail(str(a,"estimateId")); break;
            case "estimate.match": data=estimates.queryEstimateMatch(str(a,"estimateId")); break;
            case "estimate.supported_scope": data=estimates.querySupportedScope(); break;
            case "finance.customer_payments": data=customers.queryPayments(dto(a,CustomerPaymentQueryParam.class)); break;
            case "finance.supplier_payments": data=suppliers.queryPayments(dto(a,SupplierPaymentQueryParam.class)); break;
            case "finance.settlement_periods": data=settlements.querySettlementCycleList(project(a,w)); break;
            case "finance.settlement_records": data=settlementRecords(a,w); break;
            case "project.reports": data=reports.queryReportList(project(a,w),num(a,"pageNum"),num(a,"pageSize")); break;
            case "master.billing_units": data=billingUnits.queryPage(dto(a,BillingUnitRequest.class)); break;
            case "market.estimate_shortage": data=market.recommendForEstimate(dto(a,RecommendForEstimateParam.class)); break;
            case "inventory.document_detail": data=warehouseDocument(a); break;
            case "inventory.temporary_open": data=temporary.queryOpenStorageDocuments(); break;
            case "inventory.temporary_returnable": data=temporary.queryReturnableMaterials(str(a,"documentId")); break;
            case "project.site_count_detail": data=inProject(inventory.querySiteInventoryCheckTaskDetail(str(a,"taskId")),a,w); break;
            case "document.review_history": data=reviewHistory(a,w); break;
            case "project.report_detail": data=inProject(reports.queryReportInfo(str(a,"reportId")),a,w); break;
            case "finance.customer_payment_detail": data=inProject(customers.queryPaymentInfo(str(a,"paymentId")),a,w); break;
            case "finance.supplier_payment_detail": data=inProject(suppliers.queryPaymentInfo(str(a,"paymentId")),a,w); break;
            case "finance.settlement_detail": data=settlementDetail(a,w); break;
            case "project.reconciliation_records": data=reconciliationRecords(a,w); break;
            case "project.reconciliation_detail": data=reconciliationDetail(a,w); break;
            case "contract.misc_fees": data=pricing.queryContractMaterialMiscFees(project(a,w),str(a,"documentType")); break;
            case "navigation.project_workflow": data=projects.queryProjectInfo(project(a,w)); break;
            case "navigation.open": data=Collections.singletonMap("summary","业务入口已准备，请点击打开继续操作。"); break;
            case "document.export": data=exportDocument(a,w); break;
            case "estimate.export": data=exportEstimate(a); break;
            default: throw new MyBizException("该能力尚未接入执行器","AGT400");
        }
        // The card carries the same authorized service result used by the model; UI uses business labels only.
        Map<String,Object> card=new LinkedHashMap<>();
        card.put("type","business-records"); card.put("title",d.title); card.put("data",data);
        if ("navigation.open".equals(code)) card.put("navigationTarget",str(a,"target"));
        if ("navigation.project_workflow".equals(code)) card.put("navigationTarget","PROJECT_WORKFLOW");
        card.put("capabilityCode",code); card.put("dataScope",d.scope);
        card.put("pageNum",a.get("pageNum")); card.put("pageSize",a.get("pageSize"));
        card.put("scopeNote", "PUBLIC_MARKET".equals(d.scope) ? "公开商城已发布信息，报价及可供量以站点确认为准。"
                : "PROJECT_SELECTION".equals(d.scope) ? "当前选中项目内查询。" : "当前企业全部数据，不受项目选择影响。");
        Map<String,Object> criteria=new LinkedHashMap<>(a); card.put("criteria",criteria);
        String answer=d.title+"查询已完成，详情见结果卡。";
        Map<String,Object> resultMap=data==null || data instanceof Collection || data instanceof ExportFile ? Collections.emptyMap()
                : json.convertValue(data,new TypeReference<Map<String,Object>>(){});
        if (resultMap.get("totalNum") instanceof Number) answer=d.title+"共匹配 "+resultMap.get("totalNum")
                +" 项，当前展示第 "+a.getOrDefault("pageNum",1)+" 页，每页最多 "+a.getOrDefault("pageSize",20)+" 项。";
        if (data instanceof Collection && ((Collection<?>)data).isEmpty()) answer="未查到符合条件的"+d.title+"。";
        if ("market.search".equals(code) && "PRICE_ASC".equals(str(a,"sortField")))
            answer+="按相同计数和报价单位的已报价材料从低到高排列，未报价材料不参与排序。";
        AgentEvidence evidence=new AgentEvidence(); evidence.setToolCode(code); evidence.setScopeType(d.scope);
        evidence.setSelectionMode("PROJECT_SELECTION".equals(d.scope) ? "EXPLICIT" : "NOT_APPLICABLE");
        evidence.setProjectIds("PROJECT_SELECTION".equals(d.scope) ? Collections.singletonList(str(a,"projectId")) : Collections.emptyList());
        evidence.setCriteria(new LinkedHashMap<>(a));
        evidence.setSkills(Collections.singletonList("AgentBusinessQueryService"));
        evidence.setApiList(Collections.singletonList(code));
        int recordCount=data==null ? 0 : data instanceof Collection ? ((Collection<?>)data).size() : 1;
        if(resultMap.get("totalNum") instanceof Number) {
            recordCount=0;
            for(Object value:resultMap.values()) if(value instanceof Collection) {recordCount=((Collection<?>)value).size();break;}
        }
        evidence.setRecordCount(recordCount);
        AgentSkillExecution execution=new AgentSkillExecution(); execution.setIntent("BUSINESS_QUERY");
        execution.setAnswer(answer); execution.setConfidence(1.0); execution.setEvidence(evidence);
        execution.setCards(Collections.singletonList(card));
        if (data instanceof ExportFile) {
            ExportFile file=(ExportFile)data; card.put("data",Collections.singletonMap("summary","文件已生成，可在任务产物中下载。"));
            execution.setAnswer(d.title+"已生成，请在任务产物中下载。");
            execution.setArtifactType("BUSINESS_EXCEL"); execution.setArtifactTitle(file.title);
            execution.setArtifactMimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            try { execution.setArtifactContentJson(json.writeValueAsString(Collections.singletonMap("base64",file.base64))); }
            catch(Exception e) { throw new MyBizException("生成下载文件失败","AGT500"); }
        }
        return execution;
    }
    private Object warehouseDocument(Map<String,Object>a) {
        String id=str(a,"documentId");
        switch(str(a,"documentType")) {
            case "PROCUREMENT":return procurement.queryProcurementDocument(id);
            case "INBOUND":return inbound.queryInboundDocument(id);
            case "OUTBOUND":return outbound.queryOutboundDocument(id);
            case "TEMPORARY_STORAGE":return temporary.queryStorageDocument(id);
            case "TEMPORARY_RETURN":return temporary.queryReturnDocument(id);
            default:throw new MyBizException("不支持的仓库单据类型","AGT400");
        }
    }
    private Object inProject(Object value,Map<String,Object>a,AgentRuntimeRecords.Workspace w) {
        Map<String,Object> data=json.convertValue(value,new TypeReference<Map<String,Object>>(){});
        if(data==null || !project(a,w).equals(data.get("projectId")))
            throw new MyBizException("记录不存在或不属于当前项目","AGT403");
        return value;
    }
    private Object reviewHistory(Map<String,Object>a,AgentRuntimeRecords.Workspace w) {
        document(a,w);
        return reviewAudit.queryHistory(str(a,"documentId"),str(a,"documentType"));
    }
    private Object settlementDetail(Map<String,Object>a,AgentRuntimeRecords.Workspace w) {
        com.zjyz.pojo.entity.SettlementDocumentEntity entity=settlementMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.zjyz.pojo.entity.SettlementDocumentEntity>()
                .eq("settlement_document_id",str(a,"documentId")).eq("project_id",project(a,w)).eq("cid",w.getCid()));
        if(entity==null) throw new MyBizException("财务对账单不存在或不属于当前项目","AGT403");
        return settlements.querySettlementDocumentInfo(str(a,"documentId"));
    }
    private Object reconciliationRecords(Map<String,Object>a,AgentRuntimeRecords.Workspace w) {
        QueryDocumentListParam p=new QueryDocumentListParam();p.setProjectId(project(a,w));p.setQueryType("8");
        p.setPageNum(num(a,"pageNum"));p.setPageSize(num(a,"pageSize"));p.setKeyword(str(a,"keyword"));p.setReportScope("PROJECT");
        return documentStrategies.queryDocumentList(p);
    }
    private Object reconciliationDetail(Map<String,Object>a,AgentRuntimeRecords.Workspace w) {
        com.zjyz.pojo.entity.ReconciliationDocumentEntity entity=reconciliationMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.zjyz.pojo.entity.ReconciliationDocumentEntity>()
                .eq("reconciliation_document_id",str(a,"documentId")).eq("project_id",project(a,w)).eq("cid",w.getCid()));
        if(entity==null) throw new MyBizException("物料对账单不存在或不属于当前项目","AGT403");
        return reconciliation.queryReconciliationWorkbench(str(a,"documentId"));
    }
    private ExportFile exportDocument(Map<String,Object>a,AgentRuntimeRecords.Workspace w) {
        document(a,w); String id=str(a,"documentId");
        try {
            org.apache.poi.ss.usermodel.Workbook book;
            switch(str(a,"documentType")) {
                case "RENT_OUT":book=rent.exportRentDocumentExcel(id);break;
                case "RETURN":book=returns.exportReturnDocumentExcel(id);break;
                case "COMPENSATION":book=compensation.exportCompensationDocumentExcel(id);break;
                case "RENT_IN":book=rentIn.exportRentInDocumentExcel(id);break;
                case "RENT_IN_RETURN":book=rentInReturn.exportRentInReturnDocumentExcel(id);break;
                default:throw new MyBizException("不支持的导出类型","AGT400");
            }
            return file(book,"项目单据");
        } catch(MyBizException e) { throw e; }
        catch(Exception e) { throw new MyBizException("单据导出失败，请重试","AGT500"); }
    }
    private ExportFile exportEstimate(Map<String,Object>a) {
        try { return file(estimates.exportEstimateExcel(str(a,"estimateId")),"材料预估"); }
        catch(Exception e) { throw new MyBizException("预估导出失败，请重试","AGT500"); }
    }
    private ExportFile file(org.apache.poi.ss.usermodel.Workbook book,String title) throws Exception {
        try(org.apache.poi.ss.usermodel.Workbook workbook=book;java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()) {
            workbook.write(out);
            if(out.size()>8*1024*1024) throw new MyBizException("文件过大，请到业务页面导出","AGT400");
            return new ExportFile(title,Base64.getEncoder().encodeToString(out.toByteArray()));
        }
    }
    private static class ExportFile {
        final String title,base64;
        ExportFile(String title,String base64) {this.title=title;this.base64=base64;}
    }
    private Map<String,Object> parse(String raw) {
        try { return json.readValue(StringUtils.hasText(raw)?raw:"{}",new TypeReference<LinkedHashMap<String,Object>>(){}); }
        catch(Exception e) { throw new MyBizException("查询参数格式不正确","AGT400"); }
    }
    @SuppressWarnings("unchecked")
    static void validate(AgentBusinessCapabilities.Definition d, Map<String,Object> a) {
        if (a==null) throw new MyBizException("查询参数不能为空","AGT400");
        for (Map.Entry<String,Object> entry:a.entrySet()) {
            Map<String,Object> p=d.properties.get(entry.getKey()); Object value=entry.getValue();
            if(p==null) throw new MyBizException("查询包含当前能力不支持的条件，请调整筛选条件","AGT400");
            if(value==null) continue;
            String type=(String)p.get("type");
            boolean valid="string".equals(type)? value instanceof String && ((String)value).length()<=200
                : "boolean".equals(type)? value instanceof Boolean : value instanceof Number;
            if(!valid) throw new MyBizException("查询条件类型不正确："+label(entry.getKey()),"AGT400");
            if(p.containsKey("enum") && !((List<?>)p.get("enum")).contains(value))
                throw new MyBizException("查询条件取值不支持："+label(entry.getKey()),"AGT400");
            if(value instanceof Number) {
                double n=((Number)value).doubleValue();
                if(!Double.isFinite(n) || n<0 || ("integer".equals(type) && n!=Math.floor(n))
                    || (p.containsKey("minimum") && n<((Number)p.get("minimum")).doubleValue())
                    || (p.containsKey("maximum") && n>((Number)p.get("maximum")).doubleValue()))
                    throw new MyBizException("查询条件数值超出范围","AGT400");
            }
            if (entry.getKey().endsWith("Date") || "date".equals(entry.getKey())) {
                try { java.time.LocalDate.parse(value.toString()); }
                catch(Exception e) { throw new MyBizException("日期请使用年-月-日格式","AGT400"); }
            }
        }
        if(d.properties.containsKey("pageNum") && a.get("pageNum")==null) a.put("pageNum",1);
        if(d.properties.containsKey("pageSize") && a.get("pageSize")==null) a.put("pageSize",20);
        if(StringUtils.hasText(str(a,"startDate")) && StringUtils.hasText(str(a,"endDate"))
            && str(a,"startDate").compareTo(str(a,"endDate"))>0) throw new MyBizException("开始日期不能晚于结束日期","AGT400");
    }
    private String project(Map<String,Object> a,AgentRuntimeRecords.Workspace w) {
        String id=str(a,"projectId");
        if (!StringUtils.hasText(id) || w.getProjectIds()==null || !w.getProjectIds().contains(id))
            throw new MyBizException("项目不在当前选择范围内，请先选择该项目","AGT403");
        projects.queryProjectInfo(id); // Re-check current tenant ownership, including resumed runs.
        return id;
    }
    private Object progress(Map<String,Object> a,AgentRuntimeRecords.Workspace w) {
        String id=project(a,w);
        switch(str(a,"direction")) {
            case "RENT_OUT": return projects.queryRentOutProcess(id);
            case "RETURN": return projects.queryReturnProcess(id);
            case "RENT_IN": return projects.queryRentInProcess(id);
            case "RENT_IN_RETURN": return projects.queryRentInReturnProcess(id);
            default: throw new MyBizException("不支持的租还方向","AGT400");
        }
    }
    private Object documents(Map<String,Object> a,AgentRuntimeRecords.Workspace w) {
        QueryDocumentListParam p=new QueryDocumentListParam();p.setProjectId(project(a,w));
        p.setPageNum(num(a,"pageNum"));p.setPageSize(num(a,"pageSize"));p.setKeyword(str(a,"keyword"));
        Map<String,String> types=Map.of("RENT_OUT","0","RETURN","1","COMPENSATION","2","RENT_IN","9","RENT_IN_RETURN","10");
        p.setQueryType(types.get(str(a,"documentType")));return documentStrategies.queryDocumentList(p);
    }
    private Object warehouseDocuments(Map<String,Object> a) {
        QueryDocumentListParam p=new QueryDocumentListParam();p.setPageNum(num(a,"pageNum"));p.setPageSize(num(a,"pageSize"));p.setKeyword(str(a,"keyword"));
        p.setQueryType(Map.of("PROCUREMENT","5","INBOUND","6","OUTBOUND","7","TEMPORARY_STORAGE","11","TEMPORARY_RETURN","12").get(str(a,"documentType")));
        return documentStrategies.queryDocumentList(p);
    }
    private Object settlementRecords(Map<String,Object> a,AgentRuntimeRecords.Workspace w) {
        QueryDocumentListParam p=new QueryDocumentListParam();p.setProjectId(project(a,w));p.setPageNum(num(a,"pageNum"));p.setPageSize(num(a,"pageSize"));p.setKeyword(str(a,"keyword"));p.setQueryType("3");
        return settlements.queryDocumentList(p);
    }
    private Object document(Map<String,Object> a,AgentRuntimeRecords.Workspace w) {
        String id=str(a,"documentId"); Object value;
        switch(str(a,"documentType")) {
            case "RENT_OUT": value=rent.queryRentDocument(id);break;
            case "RETURN": value=returns.queryReturnDocument(id);break;
            case "COMPENSATION": value=compensation.queryCompensationDocument(id);break;
            case "RENT_IN": value=rentIn.queryRentInDocument(id);break;
            case "RENT_IN_RETURN": value=rentInReturn.queryRentInReturnDocument(id);break;
            default: throw new MyBizException("不支持的单据类型","AGT400");
        }
        Map<String,Object> record=json.convertValue(value,new TypeReference<Map<String,Object>>(){});
        if(record==null || !project(a,w).equals(record.get("projectId")))
            throw new MyBizException("单据不存在或不属于当前项目","AGT403");
        return value;
    }
    private <T> T dto(Map<String,Object>a,Class<T> type) { return json.convertValue(a,type); }
    private static String str(Map<String,Object>a,String key) { Object value=a.get(key);return value==null?null:value.toString().trim(); }
    private static Integer num(Map<String,Object>a,String key) { return a.get(key)==null?null:((Number)a.get(key)).intValue(); }
    private static String label(String key) {
        return Map.of("projectId","项目（可先查询项目清单）","materialId","材料（可先查询材料库）",
            "documentId","单据（可先查询单据列表）","documentType","单据类型","estimateId","预估记录",
            "stationCid","商城站点","mid","材料","taskId","盘点任务","direction","租还方向","categoryName","材料分类").getOrDefault(key,key);
    }
}
