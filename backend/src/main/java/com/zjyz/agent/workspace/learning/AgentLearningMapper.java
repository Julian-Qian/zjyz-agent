package com.zjyz.agent.workspace.learning;

import org.apache.ibatis.annotations.*;
import java.util.*;
import com.zjyz.agent.workspace.knowledge.AgentKnowledgeRecords;

@Mapper
public interface AgentLearningMapper {
    String COLUMNS = "e.id,e.cid,e.owner_uid AS ownerUid,e.scope_type AS scopeType,e.project_id AS projectId," +
        "e.required_projects AS requiredProjects,e.kind,e.status,e.source_id AS sourceId,e.version,e.origin_thread_id AS originThreadId," +
        "e.origin_message_id AS originMessageId,e.supersedes_id AS supersedesId,e.reason," +
        "e.evidence_chunk_id AS evidenceChunkId,e.evidence_version AS evidenceVersion,e.created_at AS createdAt,e.updated_at AS updatedAt,c.content";
    String JOIN = " FROM agent_learning_entry e JOIN agent_knowledge_chunk c ON c.source_id=e.source_id AND c.source_version=1 AND c.chunk_seq=0 ";
    @Select("SELECT " + COLUMNS + JOIN + "WHERE e.id=#{id} AND e.cid=#{cid}")
    AgentLearningModels.Entry get(@Param("cid") String cid,@Param("id") String id);
    @Select("SELECT " + COLUMNS + JOIN + "WHERE e.id=#{id} AND e.cid=#{cid} FOR UPDATE")
    AgentLearningModels.Entry getForUpdate(@Param("cid") String cid,@Param("id") String id);
    @Select("SELECT id FROM agent_learning_entry WHERE id=#{id} AND cid=#{cid} FOR UPDATE")
    String lock(@Param("cid") String cid,@Param("id") String id);
    @Select("SELECT " + COLUMNS + JOIN + "WHERE e.cid=#{cid} AND (e.owner_uid=#{uid} OR e.scope_type IN ('TENANT','PROJECT')) ORDER BY e.updated_at DESC,e.id LIMIT 1000")
    List<AgentLearningModels.Entry> candidates(@Param("cid") String cid,@Param("uid") String uid);
    @Insert("INSERT INTO agent_learning_entry (id,cid,owner_uid,scope_type,project_id,required_projects,kind,status,source_id,version,origin_thread_id,origin_message_id,supersedes_id,reason,evidence_chunk_id,evidence_version,created_at,updated_at) VALUES (#{id},#{cid},#{ownerUid},#{scopeType},#{projectId},#{requiredProjects},#{kind},#{status},#{sourceId},#{version},#{originThreadId},#{originMessageId},#{supersedesId},#{reason},#{evidenceChunkId},#{evidenceVersion},NOW(),NOW())")
    int insert(AgentLearningModels.Entry entry);
    @Update("UPDATE agent_learning_entry SET status=#{status},reason=#{reason},evidence_chunk_id=#{evidenceChunkId},evidence_version=#{evidenceVersion},version=version+1,updated_at=NOW() WHERE cid=#{cid} AND id=#{id} AND version=#{version}")
    int update(AgentLearningModels.Entry entry);
    @Select("SELECT entry_id FROM agent_learning_event WHERE cid=#{cid} AND actor_uid=#{uid} AND request_key=#{key}")
    String replay(@Param("cid") String cid,@Param("uid") String uid,@Param("key") String key);
    @Insert("INSERT INTO agent_learning_event (cid,actor_uid,request_key,entry_id,entry_version,action,snapshot_json,created_at) VALUES (#{cid},#{uid},#{key},#{id},#{version},#{action},#{snapshot},NOW())")
    int event(@Param("cid") String cid,@Param("uid") String uid,@Param("key") String key,@Param("id") String id,@Param("version") int version,@Param("action") String action,@Param("snapshot") String snapshot);
    @Select("SELECT entry_version AS version,action,snapshot_json AS snapshot,created_at AS createdAt FROM agent_learning_event WHERE cid=#{cid} AND entry_id=#{id} ORDER BY id DESC LIMIT 100")
    List<Map<String,Object>> events(@Param("cid") String cid,@Param("id") String id);
    @Insert("INSERT IGNORE INTO agent_learning_usage (cid,run_id,message_id,entry_id,entry_version,usage_type,created_at) VALUES (#{cid},#{runId},#{messageId},#{entryId},#{version},#{type},NOW())")
    int usage(@Param("cid") String cid,@Param("runId") String runId,@Param("messageId") String messageId,@Param("entryId") String entryId,@Param("version") int version,@Param("type") String type);
    @Select("SELECT " + com.zjyz.agent.dao.AgentKnowledgeMapper.CHUNK_COLUMNS + " FROM agent_knowledge_chunk c JOIN agent_knowledge_source s ON s.source_id=c.source_id WHERE c.chunk_id=#{id} AND c.status='ACTIVE' AND s.lifecycle_status='PUBLISHED' AND s.index_status='READY' AND s.active_version=1 AND c.source_version=s.version_no AND s.source_type<>'LEARNING' AND s.project_id IS NULL AND (s.scope_type='PLATFORM' OR (s.scope_type='TENANT' AND s.cid=#{cid}))")
    AgentKnowledgeRecords.Chunk evidence(@Param("cid") String cid,@Param("id") String id);
    @Select("SELECT " + com.zjyz.agent.dao.AgentKnowledgeMapper.CHUNK_COLUMNS + " FROM agent_knowledge_chunk c JOIN agent_knowledge_source s ON s.source_id=c.source_id WHERE c.chunk_id=#{id} AND c.status='ACTIVE' AND s.lifecycle_status='PUBLISHED' AND s.index_status='READY' AND s.active_version=1 AND c.source_version=s.version_no AND s.source_type<>'LEARNING' AND s.project_id IS NULL AND (s.scope_type='PLATFORM' OR (s.scope_type='TENANT' AND s.cid=#{cid})) FOR UPDATE")
    AgentKnowledgeRecords.Chunk evidenceForUpdate(@Param("cid") String cid,@Param("id") String id);
    @Select("SELECT pid AS id,person_name AS name,'PERSONNEL' AS type FROM personnel_info WHERE cid=#{cid} AND is_deleted=0 AND status=1 AND person_name=#{name} UNION ALL SELECT mid AS id,material_name AS name,'MATERIAL' AS type FROM material_info WHERE cid=#{cid} AND material_business_type='RENTAL' AND (material_status IS NULL OR material_status<>'DEPRECATED') AND material_name=#{name} LIMIT 3")
    List<Map<String,Object>> aliasTargets(@Param("cid") String cid,@Param("name") String name);
    @Select("SELECT pid AS id,person_name AS name,'PERSONNEL' AS type FROM personnel_info WHERE cid=#{cid} AND is_deleted=0 AND status=1 AND pid=#{id} UNION ALL SELECT mid AS id,material_name AS name,'MATERIAL' AS type FROM material_info WHERE cid=#{cid} AND material_business_type='RENTAL' AND (material_status IS NULL OR material_status<>'DEPRECATED') AND mid=#{id} LIMIT 3")
    List<Map<String,Object>> aliasEntity(@Param("cid") String cid,@Param("id") String id);
}
