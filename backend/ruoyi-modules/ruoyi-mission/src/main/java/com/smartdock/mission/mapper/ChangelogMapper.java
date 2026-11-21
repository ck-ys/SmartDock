package com.smartdock.mission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartdock.mission.domain.Changelog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 更新日志 Mapper（全局表，tenant.excludes 已登记）。
 *
 * @author smartdock
 */
@Mapper
public interface ChangelogMapper extends BaseMapper<Changelog> {

    /** 编辑时行锁：避免两个超管并发发布同一草稿导致重复通知。 */
    @Select("SELECT * FROM dcs_changelog WHERE log_id = #{id} AND del_flag = '0' FOR UPDATE")
    Changelog selectForUpdate(@Param("id") Long id);
}
