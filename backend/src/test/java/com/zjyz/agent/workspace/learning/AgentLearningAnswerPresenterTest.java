package com.zjyz.agent.workspace.learning;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zjyz.agent.workspace.v2.modelgateway.AgentV2ModelGateway;
import com.zjyz.agent.workspace.modelgateway.AgentModelGateway;
import com.zjyz.agent.workspace.service.AgentModelUsageService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class AgentLearningAnswerPresenterTest {
    AgentV2ModelGateway gateway=mock(AgentV2ModelGateway.class);
    AgentLearningAnswerPresenter presenter=new AgentLearningAnswerPresenter(gateway,mock(AgentModelUsageService.class),new ObjectMapper());
    List<Map<String,Object>> preferences=List.of(AgentLearningService.map("content","请用英文","status","ACTIVE"));
    AgentModelGateway.ModelResult response(String text){AgentModelGateway.ModelResult r=new AgentModelGateway.ModelResult();r.setSuccess(true);r.setContent(text);return r;}
    @Test void changedAmountsRejectRewriteWithoutSecondCall(){when(gateway.isAvailable()).thenReturn(true);when(gateway.complete(anyList(),anyList(),anyString())).thenReturn(response("Amount: 999 yuan"));assertEquals("金额100元",presenter.present("金额100元",preferences,"u",r->{}));verify(gateway,times(1)).complete(anyList(),anyList(),anyString());}
    @Test void semanticChangeRejectsRewrite(){when(gateway.isAvailable()).thenReturn(true);when(gateway.complete(anyList(),anyList(),anyString())).thenReturn(response("Amount paid: 100 yuan"),response("{\"equivalent\":false}"));assertEquals("金额100元",presenter.present("金额100元",preferences,"u",r->{}));}
    @Test void verifiedTranslationApplies(){when(gateway.isAvailable()).thenReturn(true);when(gateway.complete(anyList(),anyList(),anyString())).thenReturn(response("Amount: 100 yuan"),response("{\"equivalent\":true}"));assertEquals("Amount: 100 yuan",presenter.present("金额100元",preferences,"u",r->{}));}
    @Test void noPreferenceCostsNoCall(){assertEquals("原始回答",presenter.present("原始回答",Collections.emptyList(),"u",r->{}));verifyNoInteractions(gateway);}
}
