package org.dromara.system.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.constant.CacheNames;
import org.dromara.common.core.domain.dto.OssDTO;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.service.OssService;
import org.dromara.common.core.utils.DateUtils;
import org.dromara.common.core.utils.MapstructUtils;

import java.util.Date;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.core.utils.StreamUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.core.utils.file.FileUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.oss.core.OssClient;
import org.dromara.common.oss.entity.UploadResult;
import org.dromara.common.oss.enums.AccessPolicyType;
import org.dromara.common.oss.factory.OssFactory;
import org.dromara.system.domain.SysOss;
import org.dromara.system.domain.SysOssExt;
import org.dromara.system.domain.bo.SysOssBo;
import org.dromara.system.domain.vo.SysOssVo;
import org.dromara.system.event.OssFileUploadedEvent;
import org.dromara.system.mapper.SysOssMapper;
import org.dromara.system.service.FileGuardChecker;
import org.dromara.system.service.FileReviewGate;
import org.dromara.system.service.ISysOssService;
import org.dromara.common.satoken.utils.LoginHelper;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 文件上传 服务层实现
 *
 * @author Lion Li
 */
@RequiredArgsConstructor
@Service
public class SysOssServiceImpl implements ISysOssService, OssService {

    private final SysOssMapper baseMapper;

    /** 编程式事务：OSS HTTP 上传留在事务外，sys_oss 插入与审核建单收进同一短事务（合同#1） */
    private final TransactionTemplate transactionTemplate;

    /** 同步事件发布器：事务内发布 OssFileUploadedEvent，dcs 监听器同事务建审核单 */
    private final ApplicationEventPublisher eventPublisher;

    /** 文件审核闸门（实现方在 ruoyi-dcs；可空注入，dcs 未装配时按无审核直通，合同#5） */
    private final ObjectProvider<FileReviewGate> fileReviewGateProvider;

    /** 文件安全检测器（file-guard 批二；实现方在 ruoyi-dcs，可空注入=未装配时上传零变化） */
    private final ObjectProvider<FileGuardChecker> fileGuardCheckerProvider;

    /**
     * 查询OSS对象存储列表
     *
     * @param bo        OSS对象存储分页查询对象
     * @param pageQuery 分页查询实体类
     * @return 结果
     */
    @Override
    public TableDataInfo<SysOssVo> queryPageList(SysOssBo bo, PageQuery pageQuery) {
        LambdaQueryWrapper<SysOss> lqw = buildQueryWrapper(bo);
        Page<SysOssVo> result = baseMapper.selectVoPage(pageQuery.build(), lqw);
        List<SysOssVo> filterResult = StreamUtils.toList(result.getRecords(), this::matchingUrl);
        // 12b 审查修复：oss 管理列表同样过审核闸门——被拦文件 url 置空串，
        // 防待审文件直链经本端点外泄给仅持 system:oss:list 的角色
        applyReviewMask(filterResult);
        result.setRecords(filterResult);
        return TableDataInfo.build(result);
    }

    /**
     * 根据一组 ossIds 获取对应的 SysOssVo 列表
     *
     * <p>12b：尾部经审核闸门批量判定，被拦截文件替换为占位副本（保留 ossId/originalName，
     * url 置空串非 null，带 reviewStatus/rejectReason，合同#10）。必须新建副本而非原地改——
     * getById 返回的是 SYS_OSS 缓存同一实例，原地改会把"审核中"状态泄漏给其他用户。</p>
     *
     * @param ossIds 一组文件在数据库中的唯一标识集合
     * @return 包含 SysOssVo 对象的列表
     */
    @Override
    public List<SysOssVo> listByIds(Collection<Long> ossIds) {
        List<SysOssVo> list = new ArrayList<>();
        SysOssServiceImpl ossService = SpringUtils.getAopProxy(this);
        for (Long id : ossIds) {
            SysOssVo vo = ossService.getById(id);
            if (ObjectUtil.isNotNull(vo)) {
                try {
                    list.add(this.matchingUrl(vo));
                } catch (Exception ignored) {
                    // 如果oss异常无法连接则将数据直接返回
                    list.add(vo);
                }
            }
        }
        applyReviewMask(list);
        return list;
    }

    /**
     * 12b 审核遮蔽（listByIds / queryPageList 共用尾部）：
     * 被拦截文件替换为占位副本（保留 ossId/originalName，url 置空串非 null，带
     * reviewStatus/rejectReason，合同#10）；上传人本人单保留 url 仅补状态供前端打标。
     * 必须新建副本而非原地改——getById 返回的是 SYS_OSS 缓存同一实例，
     * 原地改会把"审核中"状态泄漏给其他用户。
     */
    private void applyReviewMask(List<SysOssVo> list) {
        FileReviewGate gate = fileReviewGateProvider.getIfAvailable();
        if (gate == null || list.isEmpty()) {
            return;
        }
        List<Long> idList = list.stream().map(SysOssVo::getOssId).toList();
        Map<Long, FileReviewGate.ReviewBlock> blocked = gate.blockedInfoMap(idList);
        for (int i = 0; i < list.size(); i++) {
            SysOssVo vo = list.get(i);
            FileReviewGate.ReviewBlock b = blocked.get(vo.getOssId());
            if (b != null) {
                list.set(i, buildReviewPlaceholder(vo, b));
            }
        }
        // 上传人自见标记：本人 PENDING/REJECTED 文件对本人可见（url 保留），仅补状态供前端打标——
        // 同样换副本，不改缓存实例
        Map<Long, FileReviewGate.ReviewBlock> owned = gate.uploaderStatusMap(idList);
        for (int i = 0; i < list.size(); i++) {
            SysOssVo vo = list.get(i);
            FileReviewGate.ReviewBlock b = owned.get(vo.getOssId());
            if (b != null && vo.getReviewStatus() == null) {
                SysOssVo marked = buildReviewPlaceholder(vo, b);
                // 与被拦占位的差异：本人文件可见，保留预签名 url
                marked.setUrl(vo.getUrl());
                list.set(i, marked);
            }
        }
    }

    /**
     * 构建被拦截文件的占位副本（不改缓存实例；字段契约见合同#10）
     */
    private SysOssVo buildReviewPlaceholder(SysOssVo vo, FileReviewGate.ReviewBlock b) {
        SysOssVo placeholder = new SysOssVo();
        placeholder.setOssId(vo.getOssId());
        placeholder.setFileName(vo.getFileName());
        placeholder.setOriginalName(vo.getOriginalName());
        placeholder.setFileSuffix(vo.getFileSuffix());
        placeholder.setService(vo.getService());
        placeholder.setCreateTime(vo.getCreateTime());
        placeholder.setCreateBy(vo.getCreateBy());
        placeholder.setCreateByName(vo.getCreateByName());
        // url 置空串（非 null/undefined），前端按 reviewStatus 打"审核中/已驳回"标记
        placeholder.setUrl("");
        placeholder.setReviewStatus(b.status());
        placeholder.setRejectReason(b.reason());
        return placeholder;
    }

    /**
     * 根据一组 ossIds 获取对应文件的 URL 列表
     *
     * <p>⚠️ 红线（12b）：本方法不过文件审核闸门，直接出预签名 URL。当前唯一调用方
     * OssUrlTranslationImpl 无人使用（全仓 VO 未挂 OSS 翻译注解）。任何新 VO 想挂
     * OSS_ID_TO_URL 翻译前必须先接入闸门，否则成为待审文件内容泄露旁路。</p>
     *
     * @param ossIds 以逗号分隔的 ossId 字符串
     * @return 以逗号分隔的文件 URL 字符串
     */
    @Override
    public String selectUrlByIds(String ossIds) {
        List<String> list = new ArrayList<>();
        SysOssServiceImpl ossService = SpringUtils.getAopProxy(this);
        for (Long id : StringUtils.splitTo(ossIds, Convert::toLong)) {
            SysOssVo vo = ossService.getById(id);
            if (ObjectUtil.isNotNull(vo)) {
                try {
                    list.add(this.matchingUrl(vo).getUrl());
                } catch (Exception ignored) {
                    // 如果oss异常无法连接则将数据直接返回
                    list.add(vo.getUrl());
                }
            }
        }
        return StringUtils.joinComma(list);
    }

    @Override
    public List<OssDTO> selectByIds(String ossIds) {
        List<OssDTO> list = new ArrayList<>();
        for (Long id : StringUtils.splitTo(ossIds, Convert::toLong)) {
            SysOssVo vo = SpringUtils.getAopProxy(this).getById(id);
            if (ObjectUtil.isNotNull(vo)) {
                try {
                    vo.setUrl(this.matchingUrl(vo).getUrl());
                    list.add(BeanUtil.toBean(vo, OssDTO.class));
                } catch (Exception ignored) {
                    // 如果oss异常无法连接则将数据直接返回
                    list.add(BeanUtil.toBean(vo, OssDTO.class));
                }
            }
        }
        return list;
    }

    private LambdaQueryWrapper<SysOss> buildQueryWrapper(SysOssBo bo) {
        Map<String, Object> params = bo.getParams();
        Date beginCreateTime = DateUtils.parseDate(params.get("beginCreateTime"));
        Date endCreateTime = DateUtils.parseDate(params.get("endCreateTime"));
        LambdaQueryWrapper<SysOss> lqw = Wrappers.lambdaQuery();
        lqw.like(StringUtils.isNotBlank(bo.getFileName()), SysOss::getFileName, bo.getFileName());
        lqw.like(StringUtils.isNotBlank(bo.getOriginalName()), SysOss::getOriginalName, bo.getOriginalName());
        lqw.eq(StringUtils.isNotBlank(bo.getFileSuffix()), SysOss::getFileSuffix, bo.getFileSuffix());
        lqw.eq(StringUtils.isNotBlank(bo.getUrl()), SysOss::getUrl, bo.getUrl());
        lqw.between(beginCreateTime != null && endCreateTime != null,
            SysOss::getCreateTime, beginCreateTime, endCreateTime);
        lqw.eq(ObjectUtil.isNotNull(bo.getCreateBy()), SysOss::getCreateBy, bo.getCreateBy());
        lqw.eq(StringUtils.isNotBlank(bo.getService()), SysOss::getService, bo.getService());
        lqw.orderByAsc(SysOss::getOssId);
        return lqw;
    }

    /**
     * 根据 ossId 从缓存或数据库中获取 SysOssVo 对象
     *
     * @param ossId 文件在数据库中的唯一标识
     * @return SysOssVo 对象，包含文件信息
     */
    @Cacheable(cacheNames = CacheNames.SYS_OSS, key = "#ossId")
    @Override
    public SysOssVo getById(Long ossId) {
        return baseMapper.selectVoById(ossId);
    }

    // 复审P0修正(批三 2026-09-22): 缓存管理器是事务感知装饰器(PlusSpringCacheManager
    // transactionAware=true), @CacheEvict 的 evict 本就自动推迟到提交后执行(回滚则跳过),
    // 无需也不应手工注册 afterCommit——在 afterCommit 回调里再走装饰器 evict 会被二次
    // 推迟注册而丢弃(快照已取), 改名后旧名滞留 30 天缓存。此前"evict 早于提交"的判断是误报。
    @CacheEvict(cacheNames = CacheNames.SYS_OSS, key = "#ossId")
    @Override
    public boolean updateOriginalName(Long ossId, String originalName) {
        if (ossId == null || originalName == null || originalName.isBlank()) {
            return false;
        }
        return baseMapper.update(null,
            Wrappers.<SysOss>lambdaUpdate()
                .set(SysOss::getOriginalName, originalName)
                .eq(SysOss::getOssId, ossId)) > 0;
    }


    /**
     * 文件下载方法，支持一次性下载完整文件
     *
     * @param ossId    OSS对象ID
     * @param response HttpServletResponse对象，用于设置响应头和向客户端发送文件内容
     */
    @Override
    public void download(Long ossId, HttpServletResponse response) throws IOException {
        checkReviewVisible(ossId);
        SysOssVo sysOss = SpringUtils.getAopProxy(this).getById(ossId);
        if (ObjectUtil.isNull(sysOss)) {
            throw new ServiceException("文件数据不存在!");
        }
        FileUtils.setAttachmentResponseHeader(response, sysOss.getOriginalName());
        response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE + "; charset=UTF-8");
        OssClient storage = OssFactory.instance(sysOss.getService());
        storage.download(sysOss.getFileName(), response.getOutputStream(), response::setContentLengthLong);
    }

    /**
     * 文件预览方法，以正确的 Content-Type 流式输出到 response
     * 与 download 的区别：不设 attachment 头、Content-Type 用真实 MIME，便于浏览器内联预览
     *
     * @param ossId    OSS对象ID
     * @param response HttpServletResponse对象
     */
    @Override
    public void preview(Long ossId, HttpServletResponse response) throws IOException {
        checkReviewVisible(ossId);
        SysOssVo sysOss = SpringUtils.getAopProxy(this).getById(ossId);
        if (ObjectUtil.isNull(sysOss)) {
            throw new ServiceException("文件数据不存在!");
        }
        // 优先用上传时存的 contentType，读不到则按后缀推断
        String contentType = null;
        SysOssExt ext = parseExt(sysOss.getExt1());
        if (ext != null && StringUtils.isNotBlank(ext.getContentType())) {
            contentType = ext.getContentType();
        }
        if (StringUtils.isBlank(contentType)) {
            contentType = FileUtils.getMimeType(sysOss.getFileSuffix());
        }
        response.setContentType(contentType);
        OssClient storage = OssFactory.instance(sysOss.getService());
        storage.download(sysOss.getFileName(), response.getOutputStream(), response::setContentLengthLong);
    }

    /**
     * 解析 SysOss.ext1（JSON 字符串）为 SysOssExt，解析失败返回 null
     */
    private SysOssExt parseExt(String ext1) {
        if (StringUtils.isBlank(ext1)) {
            return null;
        }
        try {
            return JsonUtils.parseObject(ext1, SysOssExt.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 上传 MultipartFile 到对象存储服务，并保存文件信息到数据库（来源埋点 OTHER）
     *
     * @param file 要上传的 MultipartFile 对象
     * @return 上传成功后的 SysOssVo 对象，包含文件信息
     * @throws ServiceException 如果上传过程中发生异常，则抛出 ServiceException 异常
     */
    @Override
    public SysOssVo upload(MultipartFile file) {
        return upload(file, "OTHER", null);
    }

    /**
     * 上传 MultipartFile 到对象存储服务，并保存文件信息到数据库（12b 带审核埋点）
     *
     * <p>事务边界：OSS HTTP 上传在事务外（慢操作不占连接）；sys_oss 插入与 dcs 审核建单
     * 通过 TransactionTemplate + 同步事件收进同一短事务；事务失败则补偿删除已上传对象
     * （fail-closed，不留孤儿对象，合同#1）。</p>
     *
     * @param file      要上传的 MultipartFile 对象
     * @param bizSource 来源埋点（仅展示，不作为免审依据）
     * @param missionId 关联任务ID（可空）
     * @return 上传成功后的 SysOssVo 对象，reviewStatus=PENDING/SKIPPED
     */
    @Override
    public SysOssVo upload(MultipartFile file, String bizSource, Long missionId) {
        if (ObjectUtil.isNull(file) || file.isEmpty()) {
            throw new ServiceException("上传文件不能为空");
        }
        // file-guard 前置检测（批二，Q2=C 分层混合）：拒传(DENY)以异常抛出，必须发生在
        // 对象落 MinIO 之前；强制进审(REVIEW)信号经 OssFileUploadedEvent.guardReason
        // 传给 dcs 监听器建单。dcs 未装配(guard=null)时零行为变化。
        FileGuardChecker guard = fileGuardCheckerProvider.getIfAvailable();
        String guardRisk = guard != null ? guard.checkUpload(file) : null;
        String originalfileName = file.getOriginalFilename();
        String suffix = StringUtils.substring(originalfileName, originalfileName.lastIndexOf("."), originalfileName.length());
        OssClient storage = OssFactory.instance();
        UploadResult uploadResult;
        try {
            uploadResult = storage.uploadSuffix(file.getBytes(), suffix, file.getContentType());
        } catch (IOException e) {
            throw new ServiceException(e.getMessage());
        }
        SysOssExt ext1 = new SysOssExt();
        ext1.setFileSize(file.getSize());
        ext1.setContentType(file.getContentType());
        OssFileUploadedEvent event = new OssFileUploadedEvent(originalfileName, suffix, file.getContentType(),
            file.getSize(), bizSource, missionId, LoginHelper.getUserId());
        if (guardRisk != null) {
            event.setGuardReason(guardRisk);
        }
        SysOssVo vo;
        try {
            vo = transactionTemplate.execute(st -> {
                // sys_oss 插入（雪花ID回填）→ 同步事件让 dcs 监听器同事务建审核单
                SysOssVo v = buildResultEntity(originalfileName, suffix, storage.getConfigKey(), uploadResult, ext1);
                event.setOssId(v.getOssId());
                eventPublisher.publishEvent(event);
                return v;
            });
        } catch (Exception e) {
            // 建单失败=整体失败：补偿删除已上传的 OSS 对象，防"对象在库无记录"的孤儿
            storage.delete(uploadResult.getUrl());
            throw e;
        }
        // 监听器未改写（dcs 未装配/直通）按 SKIPPED 兜底，前端统一按"免审"处理
        String reviewStatus = StringUtils.isBlank(event.getReviewStatus()) ? "SKIPPED" : event.getReviewStatus();
        vo.setReviewStatus(reviewStatus);
        return vo;
    }

    /**
     * 上传文件到对象存储服务，并保存文件信息到数据库
     *
     * <p>⚠️ 红线（12b + file-guard）：本路径不发布 OssFileUploadedEvent、不接 FileGuardChecker
     * 安全检测，绕过审核建单与拒传/进审；当前全仓库无调用方。若未来启用，必须先接入
     * 审核事件与 file-guard 前置检测，否则成为免审+免检旁路。</p>
     *
     * @param file 要上传的文件对象
     * @return 上传成功后的 SysOssVo 对象，包含文件信息
     */
    @Override
    public SysOssVo upload(File file) {
        if (ObjectUtil.isNull(file) || !file.isFile() || file.length() <= 0) {
            throw new ServiceException("上传文件不能为空");
        }
        String originalfileName = file.getName();
        String suffix = StringUtils.substring(originalfileName, originalfileName.lastIndexOf("."), originalfileName.length());
        OssClient storage = OssFactory.instance();
        long length = file.length();
        UploadResult uploadResult = storage.uploadSuffix(file, suffix);
        SysOssExt ext1 = new SysOssExt();
        ext1.setFileSize(length);
        // 保存文件信息
        return buildResultEntity(originalfileName, suffix, storage.getConfigKey(), uploadResult, ext1);
    }

    @NotNull
    private SysOssVo buildResultEntity(String originalfileName, String suffix, String configKey, UploadResult uploadResult, SysOssExt ext1) {
        SysOss oss = new SysOss();
        oss.setUrl(uploadResult.getUrl());
        oss.setFileSuffix(suffix);
        oss.setFileName(uploadResult.getFilename());
        oss.setOriginalName(originalfileName);
        oss.setService(configKey);
        oss.setExt1(JsonUtils.toJsonString(ext1));
        baseMapper.insert(oss);
        SysOssVo sysOssVo = MapstructUtils.convert(oss, SysOssVo.class);
        return this.matchingUrl(sysOssVo);
    }

    /**
     * 12b 审核可见性拦截：dcs 闸门判定不可见即拒（无闸门/功能关=直通）。
     * 放在 getById 之前——先审后可见，缓存元数据都不必取。
     */
    private void checkReviewVisible(Long ossId) {
        FileReviewGate gate = fileReviewGateProvider.getIfAvailable();
        if (gate != null && !gate.isVisibleToCurrentUser(ossId)) {
            throw new ServiceException("该文件正在审核或未通过审核，暂不可访问");
        }
    }

    /**
     * 删除OSS对象存储
     *
     * <p>12b：删除同事务级联闭单（PENDING 审核单置 REJECTED，reason='源文件已删除'，
     * 合同#11）+ 逐 id 清 SYS_OSS 缓存（防删后仍从 30 天缓存读出旧记录）。</p>
     *
     * @param ids     OSS对象ID串
     * @param isValid 判断是否需要校验
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteWithValidByIds(Collection<Long> ids, Boolean isValid) {
        if (isValid) {
            // 做一些业务上的校验,判断是否需要校验
        }
        List<SysOss> list = baseMapper.selectByIds(ids);
        for (SysOss sysOss : list) {
            OssClient storage = OssFactory.instance(sysOss.getService());
            storage.delete(sysOss.getUrl());
        }
        // 级联闭单：闸门方法不看开关（存量待审单照样闭，Q4-A）
        FileReviewGate gate = fileReviewGateProvider.getIfAvailable();
        if (gate != null) {
            gate.onOssDeleted(new ArrayList<>(ids));
        }
        // 逐 id 清缓存：须经 AOP 代理调用才生效（自调用绕过代理）。
        // 复审P1修正(批三 2026-09-22): 原先手工注册 afterCommit 再调注解 evict 是空转——
        // afterCommit 回调里同步上下文仍活跃, 事务感知装饰器会把 evict 二次推迟注册而被丢弃,
        // 删除后旧记录滞留 30 天缓存成幽灵行。缓存管理器 transactionAware=true, 直接走注解
        // evict 本就自动推迟到提交后执行(回滚跳过), 并发 getById 的灌回也会被随后的 evict 清掉。
        List<Long> evictIds = List.copyOf(ids);
        SysOssServiceImpl evictProxy = SpringUtils.getAopProxy(this);
        boolean deleted = baseMapper.deleteByIds(ids) > 0;
        if (deleted) {
            evictIds.forEach(evictProxy::evictOssCache);
        }
        return deleted;
    }

    /**
     * SYS_OSS 单条缓存清除（仅供代理调用；方法体故意为空，清缓存动作由注解完成）
     */
    @CacheEvict(cacheNames = CacheNames.SYS_OSS, key = "#ossId")
    public void evictOssCache(Long ossId) {
    }

    /**
     * 桶类型为 private 的URL 修改为临时URL时长为120s
     *
     * @param oss OSS对象
     * @return oss 匹配Url的OSS对象
     */
    private SysOssVo matchingUrl(SysOssVo oss) {
        OssClient storage = OssFactory.instance(oss.getService());
        // 仅修改桶类型为 private 的URL，临时URL时长为120s
        if (AccessPolicyType.PRIVATE == storage.getAccessPolicy()) {
            oss.setUrl(storage.createPresignedGetUrl(oss.getFileName(), Duration.ofSeconds(120)));
        }
        return oss;
    }
}
