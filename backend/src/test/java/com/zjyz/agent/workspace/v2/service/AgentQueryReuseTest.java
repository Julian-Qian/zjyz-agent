package com.zjyz.agent.workspace.v2.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AgentQueryReuseTest {
 @Test void keyOrderDoesNotRepeatButDifferentFiltersAndPaginationDo() {
  ObjectMapper json=new ObjectMapper();
  assertEquals(AgentQueryReuse.key("a","{\"page\":1,\"owner\":\"张经理\"}",json),AgentQueryReuse.key("a","{\"owner\":\"张经理\",\"page\":1}",json));
  assertNotEquals(AgentQueryReuse.key("a","{\"page\":1}",json),AgentQueryReuse.key("a","{\"page\":2}",json));
  assertNotEquals(AgentQueryReuse.key("a","{}",json),AgentQueryReuse.key("b","{}",json));
  assertNull(AgentQueryReuse.key("a","{} {}",json));
 }
}
