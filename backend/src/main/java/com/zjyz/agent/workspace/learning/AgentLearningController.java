package com.zjyz.agent.workspace.learning;

import com.zjyz.common.annotation.ZeeController;
import com.zjyz.membership.web.MembershipEntitlementRequired;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@ZeeController
@MembershipEntitlementRequired("AGENT_ENABLED")
@RequestMapping("/agent/learning")
public class AgentLearningController {
    private final AgentLearningService service;
    public AgentLearningController(AgentLearningService service){this.service=service;}
    @com.zjyz.membership.web.MembershipGraceRead
    @GetMapping("/capabilities") public Map<String,Object> capabilities(){return service.capabilities();}
    @com.zjyz.membership.web.MembershipGraceRead
    @GetMapping("/entries") public Map<String,Object> list(@RequestParam(defaultValue="1") int pageNum,
        @RequestParam(defaultValue="20") int pageSize,@RequestParam(required=false) String keyword,
        @RequestParam(required=false) String kind,@RequestParam(required=false) String status,
        @RequestParam(required=false) String scopeType,@RequestParam(required=false) String projectId){
        return service.list(pageNum,pageSize,keyword,kind,status,scopeType,projectId);
    }
    @com.zjyz.membership.web.MembershipGraceRead
    @GetMapping("/entries/{id}") public Map<String,Object> detail(@PathVariable String id){return service.detail(id);}
    @PostMapping("/entries/{id}/correct") public Map<String,Object> correct(@PathVariable String id,@RequestBody AgentLearningModels.Mutation request){return service.mutate(id,"correct",request);}
    @PostMapping("/entries/{id}/revoke") public Map<String,Object> revoke(@PathVariable String id,@RequestBody AgentLearningModels.Mutation request){return service.mutate(id,"revoke",request);}
    @PostMapping("/entries/{id}/verify") public Map<String,Object> verify(@PathVariable String id,@RequestBody AgentLearningModels.Mutation request){return service.mutate(id,"verify",request);}
    @PostMapping("/entries/{id}/publish") public Map<String,Object> publish(@PathVariable String id,@RequestBody AgentLearningModels.Mutation request){return service.mutate(id,"publish",request);}
}
