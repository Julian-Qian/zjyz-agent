package com.zjyz.agent.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.document.client.GlmOcrClient;
import com.zjyz.agent.document.model.AiDocumentIntakeModels.ExtractedMaterial;
import com.zjyz.agent.document.model.AiDocumentIntakeModels.ExtractedRentOutDocument;
import com.zjyz.agent.document.model.AiDocumentIntakeModels.MatchedMaterial;
import com.zjyz.agent.document.model.AiDocumentIntakeModels.RentOutConfirmParam;
import com.zjyz.agent.document.model.AiDocumentIntakeModels.RentOutConfirmRet;
import com.zjyz.agent.document.model.AiDocumentIntakeModels.RentOutPreviewRet;
import com.zjyz.agent.document.model.AiDocumentIntakeModels.UnmatchedMaterial;
import com.zjyz.common.exception.MyBizException;
import com.zjyz.pojo.param.req.CreateDocumentParam;
import com.zjyz.pojo.param.req.DocumentMaterialInfo;
import com.zjyz.pojo.param.req.QueryProjectMaterialForRentParam;
import com.zjyz.pojo.param.req.SaveRentDocumentParam;
import com.zjyz.pojo.param.ret.DocumentCreateInfoRet;
import com.zjyz.pojo.param.ret.MaterialForRentInfo;
import com.zjyz.pojo.param.ret.ProjectMaterialForRentRet;
import com.zjyz.pojo.param.ret.RentDocSaveResultRet;
import com.zjyz.service.RentDocumentService;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class AiDocumentIntakeService {
    private static final long MAX_FILE_BYTES = 10L * 1024L * 1024L;
    private static final Pattern DATE_PATTERN = Pattern.compile("(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private final GlmOcrClient glmOcrClient;
    private final RentDocumentService rentDocumentService;
    private final ObjectMapper objectMapper;

    public AiDocumentIntakeService(GlmOcrClient glmOcrClient,
                                   RentDocumentService rentDocumentService,
                                   ObjectMapper objectMapper) {
        this.glmOcrClient = glmOcrClient;
        this.rentDocumentService = rentDocumentService;
        this.objectMapper = objectMapper;
    }

    public RentOutPreviewRet previewRentOut(MultipartFile file, String projectId, String userInstruction) {
        validatePreviewRequest(file, projectId);
        GlmOcrClient.OcrResult ocrResult = glmOcrClient.parse(file);
        String ocrText = safe(ocrResult.getText());
        ExtractedRentOutDocument extracted = extractRentOut(ocrText, userInstruction);
        List<MaterialForRentInfo> catalog = loadRentCatalog(projectId);

        RentOutPreviewRet ret = new RentOutPreviewRet();
        ret.setProjectId(projectId);
        ret.setOcrText(ocrText);
        ret.setHead(extracted.getHead());
        ret.getWarnings().addAll(extracted.getWarnings());

        List<DocumentMaterialInfo> draftMaterials = new ArrayList<>();
        for (ExtractedMaterial row : extracted.getMaterials()) {
            MaterialForRentInfo matched = matchMaterial(catalog, row.getName(), row.getSpec());
            if (matched == null || row.getQuantity() == null || row.getQuantity() <= 0) {
                UnmatchedMaterial unmatched = new UnmatchedMaterial();
                unmatched.setRawName(row.getName());
                unmatched.setRawSpec(row.getSpec());
                unmatched.setRawUnit(row.getUnit());
                unmatched.setRawQuantity(row.getQuantity());
                unmatched.setReason(matched == null ? "未匹配到项目材料" : "数量为空或无效");
                ret.getUnmatchedRows().add(unmatched);
                continue;
            }
            MatchedMaterial matchedView = buildMatchedMaterial(row, matched);
            ret.getMaterials().add(matchedView);
            draftMaterials.add(buildDocumentMaterial(matchedView, matched));
        }

        SaveRentDocumentParam draftPayload = buildDraftPayload(projectId, extracted.getHead(), draftMaterials, ret.getWarnings());
        ret.setDraftPayload(draftPayload);
        if (draftMaterials.isEmpty()) {
            ret.getWarnings().add("未识别到可保存的项目材料");
        }
        if (!ret.getUnmatchedRows().isEmpty()) {
            ret.getWarnings().add("存在未匹配材料，请先人工处理");
        }
        ret.setCanConfirm(!draftMaterials.isEmpty() && ret.getUnmatchedRows().isEmpty());
        return ret;
    }

    public RentOutConfirmRet confirmRentOut(RentOutConfirmParam param) {
        SaveRentDocumentParam draft = param == null ? null : param.getDraftPayload();
        if (draft == null) {
            throw new MyBizException("缺少租出单草稿", "AIDOC400");
        }
        if (!StringUtils.hasText(draft.getProjectId())) {
            throw new MyBizException("项目ID不能为空", "AIDOC400");
        }
        if (CollectionUtils.isEmpty(draft.getMaterialRentInfoList())) {
            throw new MyBizException("没有可保存的材料明细", "AIDOC400");
        }
        String rentDate = StringUtils.hasText(draft.getRentDate()) ? draft.getRentDate() : LocalDate.now().toString();
        String documentName = StringUtils.hasText(draft.getRentDocumentName())
                ? draft.getRentDocumentName()
                : "AI识别租出单-" + rentDate;

        CreateDocumentParam createParam = new CreateDocumentParam();
        createParam.setType("0");
        createParam.setProjectId(draft.getProjectId());
        createParam.setDocumentName(documentName);
        createParam.setRentDate(rentDate);
        DocumentCreateInfoRet created = rentDocumentService.createDocument(draft.getProjectId(), documentName, rentDate);

        draft.setRentDocumentId(created.getDocumentId());
        draft.setRentDocumentName(created.getDocumentName());
        draft.setRentDate(rentDate);
        RentDocSaveResultRet saveResult = rentDocumentService.saveRentDocument(draft);
        if (saveResult != null && !CollectionUtils.isEmpty(saveResult.getPreCheckInfo())) {
            throw new MyBizException("租出单保存预检查未通过，请到单据详情页复核后保存", "AIDOC409");
        }

        RentOutConfirmRet ret = new RentOutConfirmRet();
        ret.setDocumentId(created.getDocumentId());
        ret.setDocumentName(created.getDocumentName());
        ret.setStatus("UNREVIEWED");
        return ret;
    }

    private void validatePreviewRequest(MultipartFile file, String projectId) {
        if (file == null || file.isEmpty()) {
            throw new MyBizException("请上传单据图片或PDF", "AIDOC400");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new MyBizException("文件不能超过10MB", "AIDOC400");
        }
        if (!StringUtils.hasText(projectId)) {
            throw new MyBizException("请选择一个项目后再识别", "AIDOC400");
        }
        String contentType = safe(file.getContentType()).toLowerCase(Locale.ROOT);
        String filename = safe(file.getOriginalFilename()).toLowerCase(Locale.ROOT);
        boolean supported = contentType.startsWith("image/")
                || contentType.contains("pdf")
                || filename.endsWith(".jpg") || filename.endsWith(".jpeg")
                || filename.endsWith(".png") || filename.endsWith(".webp")
                || filename.endsWith(".bmp") || filename.endsWith(".pdf");
        if (!supported) {
            throw new MyBizException("仅支持图片或PDF文件", "AIDOC400");
        }
    }

    private ExtractedRentOutDocument extractRentOut(String text, String userInstruction) {
        ExtractedRentOutDocument result = new ExtractedRentOutDocument();
        Map<String, Object> head = new LinkedHashMap<>();
        String merged = safe(userInstruction) + "\n" + safe(text);
        String rentDate = extractDate(merged);
        if (!StringUtils.hasText(rentDate)) {
            rentDate = LocalDate.now().toString();
            result.getWarnings().add("未识别到单据日期，已默认使用今天");
        }
        head.put("rentDate", rentDate);
        head.put("customerName", extractLineValue(merged, "承租单位", "客户", "租赁单位"));
        head.put("deliveryLocation", extractLineValue(merged, "发货地点", "发货地址", "出库地点"));
        String toLocation = extractLineValue(merged, "工程地点", "去向地点", "收货地点", "施工项目");
        head.put("toLocation", toLocation);
        head.put("constructionProject", toLocation);
        head.put("personInCharge", extractLineValue(merged, "经办人", "负责人", "制表人"));
        head.put("auditor", extractLineValue(merged, "审核人"));
        result.setHead(head);

        result.setMaterials(parseMarkdownTableMaterials(text));
        if (result.getMaterials().isEmpty()) {
            result.setMaterials(parseLooseMaterialLines(text));
        }
        if (result.getMaterials().isEmpty()) {
            result.getWarnings().add("未识别到材料明细表");
        }
        return result;
    }

    private List<ExtractedMaterial> parseMarkdownTableMaterials(String text) {
        List<String> lines = safe(text).lines().collect(Collectors.toList());
        List<ExtractedMaterial> rows = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (!line.startsWith("|") || !line.endsWith("|")) {
                continue;
            }
            List<String> headers = splitTableLine(line);
            if (!looksLikeMaterialHeader(headers)) {
                continue;
            }
            int nameIndex = findColumn(headers, "材料", "品名", "名称");
            int specIndex = findColumn(headers, "规格", "型号");
            int unitIndex = findColumn(headers, "单位");
            int quantityIndex = findColumn(headers, "数量", "件数", "根数");
            for (int j = i + 1; j < lines.size(); j++) {
                String rowLine = lines.get(j).trim();
                if (!rowLine.startsWith("|") || !rowLine.endsWith("|")) break;
                if (rowLine.matches("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)+\\|?$")) continue;
                List<String> cells = splitTableLine(rowLine);
                ExtractedMaterial row = new ExtractedMaterial();
                row.setName(cell(cells, nameIndex));
                row.setSpec(cell(cells, specIndex));
                row.setUnit(cell(cells, unitIndex));
                row.setQuantity(parseNumber(cell(cells, quantityIndex)));
                row.setConfidence(0.8d);
                if (StringUtils.hasText(row.getName()) || StringUtils.hasText(row.getSpec()) || row.getQuantity() != null) {
                    rows.add(row);
                }
            }
            break;
        }
        return rows;
    }

    private List<ExtractedMaterial> parseLooseMaterialLines(String text) {
        List<ExtractedMaterial> rows = new ArrayList<>();
        for (String rawLine : safe(text).split("\\R")) {
            String line = rawLine.trim();
            if (line.length() < 4 || line.contains("合计") || line.contains("备注")) continue;
            Matcher matcher = NUMBER_PATTERN.matcher(line);
            Double lastNumber = null;
            while (matcher.find()) {
                lastNumber = parseNumber(matcher.group());
            }
            if (lastNumber == null || lastNumber <= 0) continue;
            String cleaned = line.replaceAll("\\s+", " ");
            if (!containsAny(cleaned, "钢管", "盘扣", "轮扣", "扣件", "顶托", "方管", "工字钢", "槽钢", "脚手架")) {
                continue;
            }
            ExtractedMaterial row = new ExtractedMaterial();
            row.setName(cleaned.replaceAll(NUMBER_PATTERN.pattern() + ".*$", "").trim());
            row.setQuantity(lastNumber);
            row.setConfidence(0.55d);
            rows.add(row);
        }
        return rows;
    }

    private List<MaterialForRentInfo> loadRentCatalog(String projectId) {
        QueryProjectMaterialForRentParam param = new QueryProjectMaterialForRentParam();
        param.setProjectId(projectId);
        List<ProjectMaterialForRentRet> groups = rentDocumentService.queryProjectMaterialForRent(param);
        if (CollectionUtils.isEmpty(groups)) {
            return Collections.emptyList();
        }
        List<MaterialForRentInfo> catalog = new ArrayList<>();
        for (ProjectMaterialForRentRet group : groups) {
            if (group != null && !CollectionUtils.isEmpty(group.getMaterials())) {
                catalog.addAll(group.getMaterials());
            }
        }
        return catalog;
    }

    private MaterialForRentInfo matchMaterial(List<MaterialForRentInfo> catalog, String rawName, String rawSpec) {
        if (CollectionUtils.isEmpty(catalog)) return null;
        String name = normalize(rawName);
        String spec = normalize(rawSpec);
        MaterialForRentInfo best = null;
        int bestScore = 0;
        for (MaterialForRentInfo item : catalog) {
            String itemName = normalize(item.getMaterialName());
            String itemSpec = normalize(item.getMaterialSpecification());
            int score = 0;
            if (StringUtils.hasText(name) && itemName.equals(name)) score += 60;
            else if (StringUtils.hasText(name) && (itemName.contains(name) || name.contains(itemName))) score += 35;
            if (StringUtils.hasText(spec) && itemSpec.equals(spec)) score += 50;
            else if (StringUtils.hasText(spec) && (itemSpec.contains(spec) || spec.contains(itemSpec))) score += 25;
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return bestScore >= 35 ? best : null;
    }

    private MatchedMaterial buildMatchedMaterial(ExtractedMaterial row, MaterialForRentInfo matched) {
        double quantity = row.getQuantity() == null ? 0d : row.getQuantity();
        BigDecimal ratio = parsePositiveDecimal(matched.getConversionRatio(), BigDecimal.ONE);
        MatchedMaterial view = new MatchedMaterial();
        view.setRawName(row.getName());
        view.setRawSpec(row.getSpec());
        view.setRawUnit(row.getUnit());
        view.setRawQuantity(row.getQuantity());
        view.setMaterialId(matched.getMaterialId());
        view.setMaterialName(matched.getMaterialName());
        view.setMaterialSpecification(matched.getMaterialSpecification());
        view.setCountingUnit(matched.getCountingUnit());
        view.setPricingUnit(firstNonBlank(matched.getPricingUnit(), matched.getCountingUnit()));
        view.setConversionRatio(firstNonBlank(matched.getConversionRatio(), "1"));
        view.setCountingQuantity(quantity);
        view.setPricingQuantity(BigDecimal.valueOf(quantity).multiply(ratio).setScale(4, RoundingMode.HALF_UP).doubleValue());
        view.setConfidence(row.getConfidence());
        return view;
    }

    private DocumentMaterialInfo buildDocumentMaterial(MatchedMaterial view, MaterialForRentInfo matched) {
        DocumentMaterialInfo item = new DocumentMaterialInfo();
        item.setMaterialId(view.getMaterialId());
        item.setMaterialNumber((int) Math.round(view.getCountingQuantity()));
        item.setMaterialName(view.getMaterialName());
        item.setMaterialSpecification(view.getMaterialSpecification());
        item.setMaterialType(matched.getCategoryName());
        item.setMaterialBusinessType(firstNonBlank(matched.getMaterialBusinessType(), "RENTAL"));
        item.setMaterialUnit(view.getCountingUnit());
        item.setCountingUnit(view.getCountingUnit());
        item.setCountingQuantity(view.getCountingQuantity());
        item.setPricingUnit(view.getPricingUnit());
        item.setConversionRatio(view.getConversionRatio());
        item.setPricingQuantity(view.getPricingQuantity());
        item.setDailyRent(matched.getDailyRent());
        item.setSaleUnitPrice(matched.getSaleUnitPrice());
        item.setQuantityPerTon(matched.getQuantityPerTon());
        item.setQuantityPerTonExternal(matched.getQuantityPerTonExternal());
        item.setQuantityPerTonInternal(matched.getQuantityPerTonInternal());
        item.setWeightPerPieceExternal(matched.getWeightPerPieceExternal());
        item.setWeightPerPieceInternal(matched.getWeightPerPieceInternal());
        item.setPricingUnit1(matched.getPricingUnit1());
        item.setConversionRatio1(matched.getConversionRatio1());
        item.setPricingUnit2(matched.getPricingUnit2());
        item.setConversionRatio2(matched.getConversionRatio2());
        item.setPricingUnit3(matched.getPricingUnit3());
        item.setConversionRatio3(matched.getConversionRatio3());
        item.setMaterialSource("contract");
        item.setNote("AI拍照识别：" + firstNonBlank(view.getRawName(), view.getMaterialName()));
        return item;
    }

    private SaveRentDocumentParam buildDraftPayload(String projectId,
                                                    Map<String, Object> head,
                                                    List<DocumentMaterialInfo> materials,
                                                    List<String> warnings) {
        String rentDate = stringValue(head.get("rentDate"));
        SaveRentDocumentParam param = new SaveRentDocumentParam();
        param.setProjectId(projectId);
        param.setRentDate(firstNonBlank(rentDate, LocalDate.now().toString()));
        param.setRentDocumentName("AI识别租出单-" + param.getRentDate());
        param.setCustomerName(stringValue(head.get("customerName")));
        param.setDeliveryLocation(stringValue(head.get("deliveryLocation")));
        param.setToLocation(stringValue(head.get("toLocation")));
        param.setConstructionProject(firstNonBlank(stringValue(head.get("constructionProject")), stringValue(head.get("toLocation"))));
        param.setPersonInCharge(stringValue(head.get("personInCharge")));
        param.setAuditor(stringValue(head.get("auditor")));
        param.setNote("由AI拍照识别生成，保存前请复核。" + (CollectionUtils.isEmpty(warnings) ? "" : " 提示：" + String.join("；", warnings)));
        param.setMaterialRentInfoList(materials);
        return param;
    }

    private String extractDate(String text) {
        Matcher matcher = DATE_PATTERN.matcher(safe(text));
        if (!matcher.find()) return "";
        return String.format("%s-%02d-%02d", matcher.group(1),
                Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
    }

    private String extractLineValue(String text, String... labels) {
        for (String line : safe(text).split("\\R")) {
            String normalized = line.trim();
            for (String label : labels) {
                int index = normalized.indexOf(label);
                if (index >= 0) {
                    String value = normalized.substring(index + label.length()).replaceAll("^[：:：\\s]+", "").trim();
                    if (StringUtils.hasText(value)) return value;
                }
            }
        }
        return "";
    }

    private boolean looksLikeMaterialHeader(List<String> headers) {
        String joined = normalize(String.join("", headers));
        return (joined.contains("材料") || joined.contains("品名") || joined.contains("名称"))
                && (joined.contains("数量") || joined.contains("件数") || joined.contains("根数"));
    }

    private int findColumn(List<String> headers, String... keywords) {
        for (int i = 0; i < headers.size(); i++) {
            String header = headers.get(i);
            for (String keyword : keywords) {
                if (header.contains(keyword)) return i;
            }
        }
        return -1;
    }

    private List<String> splitTableLine(String line) {
        String value = line.trim();
        if (value.startsWith("|")) value = value.substring(1);
        if (value.endsWith("|")) value = value.substring(0, value.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String cell : value.split("\\|")) {
            cells.add(cell.trim());
        }
        return cells;
    }

    private String cell(List<String> cells, int index) {
        return index >= 0 && index < cells.size() ? cells.get(index).trim() : "";
    }

    private Double parseNumber(String value) {
        if (!StringUtils.hasText(value)) return null;
        Matcher matcher = NUMBER_PATTERN.matcher(value.replace(",", ""));
        if (!matcher.find()) return null;
        try {
            return Double.parseDouble(matcher.group());
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal parsePositiveDecimal(String value, BigDecimal fallback) {
        if (!StringUtils.hasText(value)) return fallback;
        try {
            BigDecimal parsed = new BigDecimal(value.trim());
            return parsed.compareTo(BigDecimal.ZERO) > 0 ? parsed : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) return true;
        }
        return false;
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) return "";
        return value.replaceAll("[\\s\\-_/（）()\\[\\]【】.:：,，*×xX]", "").toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return "";
    }
}
