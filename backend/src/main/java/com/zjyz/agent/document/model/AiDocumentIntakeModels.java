package com.zjyz.agent.document.model;

import com.zjyz.pojo.param.req.SaveRentDocumentParam;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class AiDocumentIntakeModels {
    @Data
    public static class RentOutPreviewRet {
        private String documentType = "RENT_OUT";
        private String projectId;
        private String ocrText;
        private Map<String, Object> head;
        private List<MatchedMaterial> materials = new ArrayList<>();
        private List<UnmatchedMaterial> unmatchedRows = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
        private SaveRentDocumentParam draftPayload;
        private boolean canConfirm;
    }

    @Data
    public static class MatchedMaterial {
        private String rawName;
        private String rawSpec;
        private String rawUnit;
        private Double rawQuantity;
        private String materialId;
        private String materialName;
        private String materialSpecification;
        private String countingUnit;
        private String pricingUnit;
        private String conversionRatio;
        private Double countingQuantity;
        private Double pricingQuantity;
        private Double confidence;
    }

    @Data
    public static class UnmatchedMaterial {
        private String rawName;
        private String rawSpec;
        private String rawUnit;
        private Double rawQuantity;
        private String reason;
    }

    @Data
    public static class RentOutConfirmParam {
        private SaveRentDocumentParam draftPayload;
    }

    @Data
    public static class RentOutConfirmRet {
        private String documentId;
        private String documentName;
        private String status;
    }

    @Data
    public static class ExtractedRentOutDocument {
        private Map<String, Object> head;
        private List<ExtractedMaterial> materials = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
    }

    @Data
    public static class ExtractedMaterial {
        private String name;
        private String spec;
        private String unit;
        private Double quantity;
        private Double confidence;
        private String note;
    }
}
