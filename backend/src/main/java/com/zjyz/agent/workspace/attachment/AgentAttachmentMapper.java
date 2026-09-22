package com.zjyz.agent.workspace.attachment;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
import com.zjyz.agent.workspace.attachment.AgentAttachmentRecords.*;

@Mapper
public interface AgentAttachmentMapper {
    String A = "attachment_id AS attachmentId,cid,owner_uid AS ownerUid,thread_id AS threadId,client_request_id AS clientRequestId,checksum,filename,mime_type AS mimeType,object_key AS objectKey,byte_size AS byteSize,version,parse_revision AS parseRevision,row_version AS rowVersion,status,created_at AS createdAt,expires_at AS expiresAt";
    String J = "job_id AS jobId,attachment_id AS attachmentId,revision,status,lease_token AS leaseToken,result_json AS resultJson,error_code AS errorCode,attempts,updated_at AS updatedAt";
    @Select("SELECT " + A + " FROM agent_attachment WHERE attachment_id=#{id}") Attachment get(String id);
    @Select("SELECT " + A + " FROM agent_attachment WHERE attachment_id=#{id} FOR UPDATE") Attachment lock(String id);
    @Select("SELECT " + A + " FROM agent_attachment WHERE cid=#{cid} AND owner_uid=#{uid} AND client_request_id=#{key}") Attachment byKey(@Param("cid") String cid,@Param("uid") String uid,@Param("key") String key);
    @Insert("INSERT INTO agent_attachment(attachment_id,cid,owner_uid,thread_id,client_request_id,checksum,filename,mime_type,object_key,byte_size,version,parse_revision,row_version,status,created_at,expires_at) VALUES(#{attachmentId},#{cid},#{ownerUid},#{threadId},#{clientRequestId},#{checksum},#{filename},#{mimeType},#{objectKey},#{byteSize},#{version},#{parseRevision},#{rowVersion},#{status},#{createdAt},#{expiresAt})") int insert(Attachment a);
    @Insert("INSERT INTO agent_attachment_parse_job(job_id,attachment_id,revision,status,attempts,updated_at) VALUES(#{jobId},#{attachmentId},#{revision},'PENDING',0,#{updatedAt})") int insertJob(ParseJob j);
    @Select("SELECT " + J + " FROM agent_attachment_parse_job WHERE attachment_id=#{id} AND revision=#{revision}") ParseJob revision(@Param("id") String id,@Param("revision") int revision);
    @Select("SELECT " + J + " FROM agent_attachment_parse_job WHERE status='PENDING' ORDER BY updated_at LIMIT 1") ParseJob pending();
    @Update("UPDATE agent_attachment_parse_job SET status='RUNNING',lease_token=#{lease},attempts=attempts+1,updated_at=#{now} WHERE job_id=#{id} AND status='PENDING'") int claim(@Param("id") String id,@Param("lease") String lease,@Param("now") LocalDateTime now);
    @Update("UPDATE agent_attachment_parse_job SET status=#{status},result_json=#{json},error_code=#{error},updated_at=#{now} WHERE job_id=#{id} AND status='RUNNING' AND lease_token=#{lease}") int finish(@Param("id") String id,@Param("lease") String lease,@Param("status") String status,@Param("json") String json,@Param("error") String error,@Param("now") LocalDateTime now);
    @Update("UPDATE agent_attachment SET status=#{status},row_version=row_version+1 WHERE attachment_id=#{id} AND parse_revision=#{revision} AND status<>'DELETED'") int parsed(@Param("id") String id,@Param("revision") int revision,@Param("status") String status);
    @Update("UPDATE agent_attachment SET status='PENDING',parse_revision=parse_revision+1,row_version=row_version+1 WHERE attachment_id=#{id} AND row_version=#{version} AND status IN ('READY','PARTIAL','FAILED')") int retry(@Param("id") String id,@Param("version") int version);
    @Update("UPDATE agent_attachment SET status='DELETED',row_version=row_version+1 WHERE attachment_id=#{id} AND status<>'DELETED'") int delete(String id);
    @Update("UPDATE agent_attachment_parse_job SET status='CANCELLED',result_json=NULL,lease_token=NULL WHERE attachment_id=#{id}") int cancelJobs(String id);
    @Update("UPDATE agent_attachment_parse_job SET status='PENDING',lease_token=NULL,updated_at=#{now} WHERE status='RUNNING' AND updated_at<#{cutoff} AND attempts<3") int recover(@Param("cutoff") LocalDateTime cutoff,@Param("now") LocalDateTime now);
    @Update("UPDATE agent_attachment_parse_job SET status='FAILED',error_code='PARSE_TIMEOUT',lease_token=NULL,updated_at=#{now} WHERE status='RUNNING' AND updated_at<#{cutoff} AND attempts>=3") int expireJobs(@Param("cutoff") LocalDateTime cutoff,@Param("now") LocalDateTime now);
    @Update("UPDATE agent_attachment a JOIN agent_attachment_parse_job j ON j.attachment_id=a.attachment_id AND j.revision=a.parse_revision SET a.status=j.status,a.row_version=a.row_version+1 WHERE a.status='PENDING' AND j.status IN ('READY','PARTIAL','FAILED')") int reconcileFailures();
    @Select("SELECT " + A + " FROM agent_attachment WHERE status<>'DELETED' AND expires_at<#{now} LIMIT 20") List<Attachment> expired(LocalDateTime now);
    @Select("SELECT " + A + " FROM agent_attachment WHERE status='DELETED' AND object_key<>'' LIMIT 20") List<Attachment> cleanup();
    @Update("UPDATE agent_attachment SET object_key='' WHERE attachment_id=#{id} AND status='DELETED'") int cleaned(String id);
}
