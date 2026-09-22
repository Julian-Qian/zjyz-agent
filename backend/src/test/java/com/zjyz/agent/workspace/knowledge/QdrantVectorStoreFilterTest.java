package com.zjyz.agent.workspace.knowledge;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class QdrantVectorStoreFilterTest {
    @Test void tenantAndCurrentPointAllowListAreBothAppliedBeforeRanking() {
        Map<String,Object> filter=QdrantVectorStore.searchFilter("tenant-a",Collections.singletonList("current-point"));
        List<?> must=(List<?>)filter.get("must");assertEquals(2,must.size());
        assertTrue(must.toString().contains("tenant-a"));assertTrue(must.toString().contains("current-point"));
        assertFalse(must.toString().contains("old-point"));
    }
    @Test void platformSearchAlsoRestrictsCurrentPointIds() {
        List<?> must=(List<?>)QdrantVectorStore.searchFilter(null,Arrays.asList("p1","p2")).get("must");
        assertEquals(Collections.singletonMap("has_id",Arrays.asList("p1","p2")),must.get(0));
    }
}
