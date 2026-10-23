package com.smartdock.dcs.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdock.dcs.domain.DcsNotice;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 微核心: 站内通知 Mapper(无 jsonb 列, MP 标准方法全够; 幂等键插入除外)。
 *
 * @author smartdock
 */
@Mapper
public interface DcsNoticeMapper extends BaseMapper<DcsNotice> {

    /**
     * 带业务幂等键的插入（批4 回修）：同 (tenant_id, user_id, dedup_key) 已存在则跳过。
     *
     * <p>ON CONFLICT 语法与 ProfileEventMapper.insertIgnore 同款（租户拦截器兼容先例）；
     * 部分唯一索引限定 dedup_key IS NOT NULL，普通通知（无键）不受约束。</p>
     */
    @Insert("""
        INSERT INTO dcs_notice
            (id, tenant_id, user_id, notice_type, title, content, task_id, read_flag, create_time, dedup_key, ref_url)
        VALUES
            (#{n.id}, #{n.tenantId}, #{n.userId}, #{n.noticeType}, #{n.title}, #{n.content},
             #{n.taskId}, #{n.readFlag}, #{n.createTime}, #{n.dedupKey}, #{n.refUrl})
        ON CONFLICT (tenant_id, user_id, dedup_key) WHERE dedup_key IS NOT NULL DO NOTHING
        """)
    int insertIgnoreDedup(@Param("n") DcsNotice n);
}
