package com.zjyz.agent.workspace.finance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.finance.ReceivableCollectionModels.PeriodItem;
import com.zjyz.agent.workspace.finance.ReceivableCollectionModels.ProjectItem;
import com.zjyz.agent.workspace.finance.ReceivableCollectionModels.Result;
import com.zjyz.agent.workspace.finance.ReceivableCollectionModels.Summary;
import com.zjyz.agent.workspace.model.AgentRuntimeRecords;
import com.zjyz.common.exception.MyBizException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

@Component
public class ReceivableCollectionArtifactExporter {
    private final ObjectMapper objectMapper;

    public ReceivableCollectionArtifactExporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public byte[] export(AgentRuntimeRecords.Artifact artifact) {
        if (artifact != null && "BUSINESS_EXCEL".equals(artifact.getArtifactType())) {
            try {
                byte[] bytes=java.util.Base64.getDecoder().decode(
                        objectMapper.readTree(artifact.getContentJson()).path("base64").asText());
                if(bytes.length==0 || bytes.length>8*1024*1024) throw new IllegalArgumentException();
                return bytes;
            } catch(Exception e) { throw new MyBizException("下载文件不可用，请重新生成","AGT400"); }
        }
        if (artifact == null || !ReceivableCollectionSkill.ARTIFACT_TYPE.equals(artifact.getArtifactType())) {
            throw new MyBizException("该产物不支持导出应收催缴 Excel", "AGT400");
        }
        try {
            Result result = objectMapper.readValue(artifact.getContentJson(), Result.class);
            try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                CellStyle header = headerStyle(workbook);
                buildSummary(workbook, result, header);
                buildProjects(workbook, result, header);
                buildPeriods(workbook, result, header);
                buildCriteria(workbook, result, header);
                workbook.write(output);
                return output.toByteArray();
            }
        } catch (MyBizException e) {
            throw e;
        } catch (Exception e) {
            throw new MyBizException("应收催缴 Excel 生成失败", "FIN500");
        }
    }

    private void buildSummary(Workbook workbook, Result result, CellStyle header) {
        Sheet sheet = workbook.createSheet("汇总");
        row(sheet, 0, header, "指标", "值");
        Summary s = result.getSummary();
        int index = 1;
        index = keyValue(sheet, index, "截止日期", result.getQuery().getAsOfDate());
        index = keyValue(sheet, index, "符合条件项目数", s.getProjectCount());
        index = keyValue(sheet, index, "导出项目数", s.getReturnedProjectCount());
        index = keyValue(sheet, index, "逾期未清总额", s.getTotalOutstanding());
        index = keyValue(sheet, index, "本年到期未清", s.getCurrentYearOutstanding());
        index = keyValue(sheet, index, "历史结转未清", s.getCarryoverOutstanding());
        index = keyValue(sheet, index, "P1项目数", s.getP1Count());
        index = keyValue(sheet, index, "P2项目数", s.getP2Count());
        index = keyValue(sheet, index, "P3项目数", s.getP3Count());
        keyValue(sheet, index, "最长逾期天数", s.getMaxOverdueDays());
        autosize(sheet, 2);
    }

    private void buildProjects(Workbook workbook, Result result, CellStyle header) {
        Sheet sheet = workbook.createSheet("项目清单");
        List<String> columns = Arrays.asList("优先级", "项目编号", "项目名称", "客户", "负责人", "项目状态", "年度分组",
                "逾期未清金额", "本年到期未清", "历史结转未清", "最长逾期天数", "未清账期数", "最早到期日", "最近回款日", "建议动作");
        row(sheet, 0, header, columns.toArray(new Object[0]));
        int index = 1;
        for (ProjectItem item : result.getItems()) {
            row(sheet, index++, null, item.getPriority(), item.getProjectId(), item.getProjectName(), item.getCustomerName(),
                    item.getManagerName(), item.getProjectStatus(), item.getYearGroup(), item.getOutstandingAmount(),
                    item.getCurrentYearOutstanding(), item.getCarryoverOutstanding(), item.getOverdueDays(), item.getPeriodCount(),
                    item.getEarliestDueDate(), item.getLatestPaymentDate(), item.getSuggestedAction());
        }
        sheet.createFreezePane(0, 1);
        autosize(sheet, columns.size());
    }

    private void buildPeriods(Workbook workbook, Result result, CellStyle header) {
        Sheet sheet = workbook.createSheet("账期明细");
        List<String> columns = Arrays.asList("项目编号", "项目名称", "客户", "优先级", "账期ID", "结算单ID", "账期开始",
                "账期结束", "到期日", "逾期天数", "应收本金", "截止日已核销", "截止日未清", "年度分组");
        row(sheet, 0, header, columns.toArray(new Object[0]));
        int index = 1;
        for (ProjectItem project : result.getItems()) {
            for (PeriodItem period : project.getPeriods()) {
                row(sheet, index++, null, project.getProjectId(), project.getProjectName(), project.getCustomerName(), project.getPriority(),
                        period.getPeriodId(), period.getSettlementDocumentId(), period.getPeriodStart(), period.getPeriodEnd(),
                        period.getDueDate(), period.getOverdueDays(), period.getPrincipalAmount(), period.getAllocatedAsOf(),
                        period.getOutstandingAsOf(), period.getYearGroup());
            }
        }
        sheet.createFreezePane(0, 1);
        autosize(sheet, columns.size());
    }

    private void buildCriteria(Workbook workbook, Result result, CellStyle header) {
        Sheet sheet = workbook.createSheet("口径与告警");
        row(sheet, 0, header, "项目", "内容");
        int index = 1;
        index = keyValue(sheet, index, "asOfDate", result.getQuery().getAsOfDate());
        index = keyValue(sheet, index, "year", result.getQuery().getYear());
        index = keyValue(sheet, index, "scope", result.getQuery().getScope());
        index = keyValue(sheet, index, "minOutstandingAmount", result.getQuery().getMinOutstandingAmount());
        index = keyValue(sheet, index, "minOverdueDays", result.getQuery().getMinOverdueDays());
        index = keyValue(sheet, index, "projectKeyword", result.getQuery().getProjectKeyword());
        index = keyValue(sheet, index, "customerKeyword", result.getQuery().getCustomerKeyword());
        index = keyValue(sheet, index, "managerName", result.getQuery().getManagerName());
        index = keyValue(sheet, index, "priorityLevels", String.join(",", result.getQuery().getPriorityLevels()));
        index = keyValue(sheet, index, "sortBy", result.getQuery().getSortBy());
        index = keyValue(sheet, index, "口径说明", "未清金额按截止日及以前的ACTIVE核销记录重新计算；到期日采用应收账期due_date。");
        for (String warning : result.getWarnings()) index = keyValue(sheet, index, "告警", warning);
        autosize(sheet, 2);
    }

    private int keyValue(Sheet sheet, int index, String key, Object value) {
        row(sheet, index, null, key, value);
        return index + 1;
    }

    private void row(Sheet sheet, int rowIndex, CellStyle style, Object... values) {
        Row row = sheet.createRow(rowIndex);
        for (int index = 0; index < values.length; index++) {
            Cell cell = row.createCell(index);
            Object value = values[index];
            if (value instanceof BigDecimal) cell.setCellValue(((BigDecimal) value).doubleValue());
            else if (value instanceof Number) cell.setCellValue(((Number) value).doubleValue());
            else cell.setCellValue(value == null ? "" : String.valueOf(value));
            if (style != null) cell.setCellStyle(style);
        }
    }

    private CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.LIGHT_CORNFLOWER_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private void autosize(Sheet sheet, int columns) {
        for (int index = 0; index < columns; index++) {
            sheet.autoSizeColumn(index);
            sheet.setColumnWidth(index, Math.min(sheet.getColumnWidth(index) + 512, 16000));
        }
    }
}
