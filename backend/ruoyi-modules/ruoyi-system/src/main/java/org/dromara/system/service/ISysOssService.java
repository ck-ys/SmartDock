package org.dromara.system.service;

import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.system.domain.bo.SysOssBo;
import org.dromara.system.domain.vo.SysOssVo;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * 文件上传 服务层
 *
 * @author Lion Li
 */
public interface ISysOssService {

    /**
     * 查询OSS对象存储列表
     *
     * @param sysOss    OSS对象存储分页查询对象
     * @param pageQuery 分页查询实体类
     * @return 结果
     */
    TableDataInfo<SysOssVo> queryPageList(SysOssBo sysOss, PageQuery pageQuery);

    /**
     * 根据一组 ossIds 获取对应的 SysOssVo 列表
     *
     * @param ossIds 一组文件在数据库中的唯一标识集合
     * @return 包含 SysOssVo 对象的列表
     */
    List<SysOssVo> listByIds(Collection<Long> ossIds);

    /**
     * 根据 ossId 从缓存或数据库中获取 SysOssVo 对象
     *
     * @param ossId 文件在数据库中的唯一标识
     * @return SysOssVo 对象，包含文件信息
     */
    SysOssVo getById(Long ossId);
    /**
     * 仅修改 OSS 元数据的原始文件名(不移动存储对象)
     * <p>DCS 附件命名规则(Q5④ 2026-09-22 批三): Excel/在线导入的附件按字段级
     * namingRule 改写展示名, 与弹窗交互上传改名的可见效果对齐。</p>
     *
     * @param ossId        OSS 主键
     * @param originalName 新原始文件名(已按规则生成, 含扩展名)
     * @return 是否实际更新到行
     */
    boolean updateOriginalName(Long ossId, String originalName);

    /**
     * 上传 MultipartFile 到对象存储服务，并保存文件信息到数据库
     *
     * @param file 要上传的 MultipartFile 对象
     * @return 上传成功后的 SysOssVo 对象，包含文件信息
     */
    SysOssVo upload(MultipartFile file);

    /**
     * 上传 MultipartFile 到对象存储服务，并保存文件信息到数据库（12b 带审核埋点）
     *
     * <p>bizSource/missionId 仅作来源埋点与任务关联，不作为免审依据（前端可伪造）。
     * 审核开启时 sys_oss 插入与审核单建单在同一事务（fail-closed），见 OssFileUploadedEvent。</p>
     *
     * @param file      要上传的 MultipartFile 对象
     * @param bizSource 来源埋点（MISSION_ATTACHMENT/DYNAMIC_FIELD/AVATAR/CHAT/OTHER）
     * @param missionId 关联任务ID（可空，Q2-A 任务审核人放行判定用）
     * @return 上传成功后的 SysOssVo 对象，reviewStatus 标记 PENDING/SKIPPED
     */
    SysOssVo upload(MultipartFile file, String bizSource, Long missionId);

    /**
     * 上传文件到对象存储服务，并保存文件信息到数据库
     *
     * @param file 要上传的文件对象
     * @return 上传成功后的 SysOssVo 对象，包含文件信息
     */
    SysOssVo upload(File file);

    /**
     * 文件下载方法，支持一次性下载完整文件
     *
     * @param ossId    OSS对象ID
     * @param response HttpServletResponse对象，用于设置响应头和向客户端发送文件内容
     */
    void download(Long ossId, HttpServletResponse response) throws IOException;

    /**
     * 文件预览方法，以正确的 Content-Type 流式输出到 response（桶可保持 private）
     *
     * @param ossId    OSS对象ID
     * @param response HttpServletResponse对象，用于设置响应头和向客户端发送文件内容
     */
    void preview(Long ossId, HttpServletResponse response) throws IOException;

    /**
     * 删除OSS对象存储
     *
     * @param ids     OSS对象ID串
     * @param isValid 判断是否需要校验
     * @return 结果
     */
    Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid);

}
