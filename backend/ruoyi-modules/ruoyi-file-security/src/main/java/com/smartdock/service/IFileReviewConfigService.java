package com.smartdock.service;

import com.smartdock.domain.FileReviewConfig;
import com.smartdock.domain.bo.FileReviewConfigBo;
import com.smartdock.domain.vo.FileReviewConfigVo;

/**
 * 租户级文件审核配置服务（12b 文件审核中心 P1）
 *
 * @author smartdock
 */
public interface IFileReviewConfigService {

    /**
     * 取当前租户生效配置（上传钩子/读拦截共用入口）
     *
     * <p>查无行（租户从未开过）或 enabled != '1' 均代表"功能整体关闭"：
     * 钩子不建单、拦截直通、头像走原立即生效路径（合同#14）。
     * 查无行返回关闭态常量对象，【不落库】——首行由设置页保存时 upsert 产生。</p>
     *
     * @return 生效配置（永不返回 null）
     */
    FileReviewConfig getEffective();

    /**
     * 设置页读取：配置 + 实时统计（待审数/审核员数，供"关闭影响面"与"无审核员"警告）
     */
    FileReviewConfigVo getConfig();

    /**
     * 设置页保存：幂等 upsert（ON CONFLICT (tenant_id) DO UPDATE，两管理员并发首存不炸 UNIQUE）
     */
    void saveConfig(FileReviewConfigBo bo);
}
