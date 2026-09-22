package com.zjyz.agent.document.controller;

import com.zjyz.agent.document.model.AiDocumentIntakeModels.RentOutConfirmParam;
import com.zjyz.agent.document.model.AiDocumentIntakeModels.RentOutConfirmRet;
import com.zjyz.agent.document.model.AiDocumentIntakeModels.RentOutPreviewRet;
import com.zjyz.agent.document.service.AiDocumentIntakeService;
import com.zjyz.common.annotation.ZeeController;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@ZeeController
@RequestMapping("/agent/document-intake")
@Api(tags = "Agent AI拍照开单")
@com.zjyz.membership.web.MembershipEntitlementRequired("AGENT_ENABLED")
public class AiDocumentIntakeController {
    private final AiDocumentIntakeService intakeService;

    public AiDocumentIntakeController(AiDocumentIntakeService intakeService) {
        this.intakeService = intakeService;
    }

    @PostMapping("/rent-out/preview")
    @com.zjyz.membership.web.MembershipAiOperation("AI拍照识别租出单")
    @ApiOperation("AI拍照识别租出单并生成预览")
    public RentOutPreviewRet previewRentOut(@RequestParam("file") MultipartFile file,
                                            @RequestParam("projectId") String projectId,
                                            @RequestParam(value = "userInstruction", required = false) String userInstruction) {
        return intakeService.previewRentOut(file, projectId, userInstruction);
    }

    @PostMapping("/rent-out/confirm")
    @com.zjyz.membership.web.MembershipAiOperation("AI拍照确认生成租出单")
    @ApiOperation("确认AI拍照识别结果并生成未审核租出单")
    public RentOutConfirmRet confirmRentOut(@RequestBody RentOutConfirmParam param) {
        return intakeService.confirmRentOut(param);
    }
}
