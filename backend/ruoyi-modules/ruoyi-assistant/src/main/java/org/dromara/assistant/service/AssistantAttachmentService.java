package org.dromara.assistant.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.domain.DcsAssistantAttachment;
import org.dromara.assistant.domain.DcsAssistantConversation;
import org.dromara.assistant.mapper.AssistantAttachmentMapper;
import org.dromara.assistant.mapper.AssistantConversationMapper;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.assistant.service.support.OssTextExtractor;
import org.dromara.system.domain.vo.SysOssVo;
import org.dromara.system.service.ISysOssService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 助手会话附件服务（附件分析批 ATTACH-1，2026-10-07，grill 8 题全 A）。
 *
 * <p>链路：前端调通用 OSS 上传（bizSource=ASSISTANT 免审 EXEMPT）拿 ossId →
 * 本服务登记（thread 绑定+属主校验+每会话≤3）→ 批E 起登记秒回、解析挪后台
 * 串行队列（OssTextExtractor 截 8000 行落库，防并发大文件 OOM）。解析失败落
 * FAILED 可重试，不回滚登记行。</p>
 *
 * <p>上限口径（Q5 拍板）：单文件 10MB（sys_oss 无 size 字段，读取时由
 * extractUncapped 的 maxBytes 拒并落人话 parse_error；前端 input 预校验双保险）；
 * 每会话同时挂 3 个，超出拒并提示先移除旧附件。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantAttachmentService {

    /** 每会话同时挂载上限（Q5 拍板） */
    public static final int MAX_PER_THREAD = 3;
    /** 单文件字节上限（Q5 拍板 10MB；读取时拒绝口径与文案一致） */
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    /** 附件解析结果截行上限(批3 自 dcs 迁入, 原值 8000 不变) */
    private static final int ATTACHMENT_RESULT_MAX_LINES = 8000;

    /**
     * 批E(AU-21 A, 1008 拍板)：解析串行后台队列——单线程排队跑长 IO，
     * 防多个大文件并发解析 OOM（复用 0930 附件对比 MQ 消费端的串行配方）。
     * register 因此秒回（登记即返回 PENDING），前端 chip 轮询到终态。
     */
    private static final ExecutorService PARSE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "assistant-attach-parse");
        t.setDaemon(true);
        return t;
    });

    /** 僵尸解析自愈阈值（G4 批G 1008 起只判「已开始解析后」超时，排队不计入） */
    private static final long PENDING_STALE_MS = 10 * 60_000L;

    /** 089/ISSUE-F14-09 修：助手场景超限话术——"文件级对比"是附件对比功能的话术，
     *  串台到助手解析会误导用户（超限≠能对比，是解析不了） */
    private static final String OVER_LIMIT_MSG = "文件超过 " + (MAX_BYTES / 1024 / 1024)
        + "MB，暂不支持解析为文本，请拆分文件后再试";

    /** 088/ISSUE-F14-08 修：在跑解析登记（毒丸自愈用）——id→开工时刻/future */
    private final ConcurrentHashMap<Long, Long> runningSince = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Future<?>> runningFutures = new ConcurrentHashMap<>();

    private final AssistantAttachmentMapper attachmentMapper;
    private final AssistantConversationMapper conversationMapper;
    private final ISysOssService sysOssService;
    private final OssTextExtractor textExtractor;

    /**
     * 登记并异步解析：幂等（同会话同文件返回现状；软删行复活重解析 G2）；后缀白名单；
     * 文件属主校验（G1：只能挂自己上传的文件）；thread 归属校验；超 3 个拒。
     */
    public DcsAssistantAttachment register(String tenantId, Long userId, String threadId, Long ossId) {
        if (threadId == null || threadId.isBlank() || ossId == null) {
            throw new ServiceException("缺少文件或会话标识");
        }
        // 幂等+复活判定（G2 批G 1008）：查重含软删行——唯一索引 uk(tenant,thread,oss)
        // 不含 del_flag，对软删行 insert 必撞约束 500，须走「复活即重解析」而非新插入
        DcsAssistantAttachment exists = attachmentMapper.selectByOssAny(tenantId, threadId, ossId);
        if (exists != null && "0".equals(exists.getDelFlag())) {
            // 086/ISSUE-F14-06 修：幂等短路补属主谓词——原版直接 return 跳过了下面的
            // thread 归属/G1 文件属主校验，同租户他人猜中 (threadId,ossId) 可读附件元数据
            if (!userId.equals(exists.getUserId())) {
                throw new ServiceException("无权向该会话添加附件");
            }
            return exists;
        }
        // thread 归属：会话已建且属主非本人 → 拒（防把附件挂进他人会话）
        DcsAssistantConversation conv = conversationMapper.selectByThread(tenantId, threadId);
        if (conv != null && !conv.getUserId().equals(userId)) {
            throw new ServiceException("无权向该会话添加附件");
        }
        SysOssVo oss = sysOssService.getById(ossId);
        if (oss == null || oss.getOssId() == null) {
            throw new ServiceException("文件不存在或已删除");
        }
        // G1(P0 越权读, 批G 1008)：登记时比对文件属主——此前只查存在性，同租户任何人
        // 拿他人 ossId 就能让 AI 读到全文（掩码只挡姓名手机，工号成绩裸出）。
        // 不一致按「文件不存在」拒绝（与不存在同文案，防探测枚举他人附件编号）
        if (!userId.equals(oss.getCreateBy())) {
            throw new ServiceException("文件不存在或已删除");
        }
        String suffix = normalizeSuffix(oss.getFileSuffix());
        if (!OssTextExtractor.isSupportedSuffix(suffix)) {
            throw new ServiceException("暂不支持该格式，可上传 docx/xlsx/pptx/pdf/txt");
        }
        // 挂载位判定：新插入与复活都占位（幂等命中既有活跃行不占新位，提前 return 了）
        List<DcsAssistantAttachment> current = attachmentMapper.listByThread(tenantId, threadId, userId);
        if (current.size() >= MAX_PER_THREAD) {
            throw new ServiceException("每个会话最多挂 " + MAX_PER_THREAD + " 个附件，请先移除旧文件");
        }
        // G2：软删行复活——显式 UPDATE 回 del_flag='0'（revive 带 del_flag='1' 守卫，
        // 列显式不回写快照，R14-F14-01 自毁根因已拔）；解析按 id 排队，后台不再持有
        // 本内存对象（R14-F14-07：HTTP 线程返回的实体与解析线程解耦）
        if (exists != null) {
            exists.setFileName(oss.getOriginalName());
            exists.setSuffix(suffix);
            exists.setParseStatus("PENDING");
            exists.setParseError(null);
            exists.setParsedText(null);
            exists.setLineCount(0);
            exists.setDelFlag("0");
            attachmentMapper.revive(exists.getId(), exists.getFileName(), exists.getSuffix());
            submitParse(exists.getId(), oss);
            return exists;
        }
        DcsAssistantAttachment row = new DcsAssistantAttachment();
        row.setTenantId(tenantId);
        row.setThreadId(threadId.trim());
        row.setUserId(userId);
        row.setOssId(ossId);
        row.setFileName(oss.getOriginalName());
        row.setFileSize(0L);
        row.setSuffix(suffix);
        row.setParseStatus("PENDING");
        try {
            attachmentMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 085/ISSUE-F14-05 修：并发 register 同 (tenant,thread,oss) 撞 uk——赢家已
            // 落行，败家重查按幂等语义返回现状（原版上抛=全局"发生未知异常"，用户拿
            // 不到可行动信息）。重查仍空=极端时序，按登记冲突给人话
            DcsAssistantAttachment winner = attachmentMapper.selectByOssAny(tenantId, threadId, ossId);
            if (winner != null && "0".equals(winner.getDelFlag())) {
                return winner;
            }
            throw new ServiceException("附件正在处理中，请稍候再试");
        }
        // 批E(AU-21 A)：登记秒回，解析挪后台串行队列；幂等重入返回既有行不重复排队
        submitParse(row.getId(), oss);
        return row;
    }

    /** 会话附件清单（旧→新，属主谓词；前端文件卡回放+工具读取共用）。
     *  G4(批G 1008)：僵尸只判「已开始解析(PARSING)后超时」——update_time 由 parse()
     *  开工时刷新即计时起点；排队(PENDING)再久不误报（单线程队列积压是常态），
     *  重启丢队列由 {@link #recoverPendingOnStartup()} 重排自愈。 */
    public List<DcsAssistantAttachment> listOwned(String tenantId, Long userId, String threadId) {
        List<DcsAssistantAttachment> rows = attachmentMapper.listByThread(tenantId, threadId, userId);
        Date now = new Date();
        for (DcsAssistantAttachment r : rows) {
            if ("PARSING".equals(r.getParseStatus()) && r.getUpdateTime() != null
                && now.getTime() - r.getUpdateTime().getTime() > PENDING_STALE_MS) {
                r.setParseStatus("FAILED");
                r.setParseError("解析超时（可能上传后服务重启），请移除后重新上传");
                // R14-F14-04 修：显式列失败回写，不再 updateById 整实体（快照 del_flag='0'
                // 会把读取间隙被移除的行写活）
                attachmentMapper.finishParseFailure(r.getId(), r.getParseError());
            }
        }
        return rows;
    }

    /**
     * G4(批G 1008)：启动恢复——进程重启会丢 PARSE_EXECUTOR 内存队列，滞留的
     * PENDING/PARSING 行永无终态（前端永远「解析中」）。启动时全量重排队自愈，
     * 重跑 parse 幂等覆盖状态；源文件已删的行直接判 FAILED 人话。
     */
    @PostConstruct
    public void recoverPendingOnStartup() {
        List<DcsAssistantAttachment> stuck = attachmentMapper.listStuck();
        for (DcsAssistantAttachment row : stuck) {
            SysOssVo oss = sysOssService.getById(row.getOssId());
            if (oss == null || oss.getOssId() == null) {
                // R14-F14-04 修：显式列失败回写，不整实体（快照 del_flag 复活面同上）
                attachmentMapper.finishParseFailure(row.getId(), "源文件已删除，请移除后重新上传");
                continue;
            }
            submitParse(row.getId(), oss);
        }
        if (!stuck.isEmpty()) {
            log.info("[AssistantAttachment] 启动恢复：重排队 {} 条滞留解析行", stuck.size());
        }
    }

    /**
     * 088/ISSUE-F14-08 修（2026-10-14）：解析看门狗——单线程队列毒丸自愈。
     * 原版无任务级超时：一个卡死的解析（坏文件/存储端挂起）永久占住唯一队列线程，
     * 其后所有附件永远"解析中"，仅重启脱困且重启后同文件再卡。本巡检对开工超
     * {@link #PENDING_STALE_MS} 的任务 future.cancel(true)（中断阻塞 IO，多数场景
     * 随即抛错出队）并落 FAILED 人话行——即使极顽固 IO 不响应中断，行也不再误导。
     * 与 listOwned 的读时僵尸判定互补（本路=服务端主动，那条=客户端读时兜底）。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 120_000)
    public void parseWatchdog() {
        long now = System.currentTimeMillis();
        runningSince.forEach((id, since) -> {
            if (now - since <= PENDING_STALE_MS) {
                return;
            }
            Future<?> f = runningFutures.get(id);
            if (f != null && f.cancel(true)) {
                attachmentMapper.finishParseFailure(id, "解析超时（文件过大或服务器繁忙），请移除后重新上传");
                log.warn("[AssistantAttachment] 看门狗取消超时解析 id={}（{} 分钟），队列已让位", id, PENDING_STALE_MS / 60_000);
            }
        });
    }

    /** 统一入队口（088 修）：登记在跑任务供看门狗取消；finally 摘除防泄漏 */
    private void submitParse(Long id, SysOssVo oss) {
        Future<?> f = PARSE_EXECUTOR.submit(() -> {
            runningSince.put(id, System.currentTimeMillis());
            try {
                parse(id, oss);
            } finally {
                runningSince.remove(id);
                runningFutures.remove(id);
            }
        });
        runningFutures.put(id, f);
    }

    /**
     * 移除会话附件（AU-05A）：登记超限一直提示「请先移除旧文件」但移除通道不存在——
     * 用户挂满 3 个后永久被拒只能弃会话。软删腾挂载位，行由 180 天清理统一回收；
     * 属主+租户谓词在 UPDATE 内原子判定，影响行数=0 统一话术（防他人附件探测）。
     */
    public void remove(String tenantId, Long userId, Long attachmentId) {
        if (attachmentId == null) {
            throw new ServiceException("缺少附件标识");
        }
        int n = attachmentMapper.softDeleteOwned(tenantId, userId, attachmentId);
        if (n == 0) {
            throw new ServiceException("附件不存在或无权操作");
        }
    }

    /** 异步解析并回写状态：开工先置 PARSING（G4 计时起点，0 行=排队期间被移除即跳过）；
     *  成功=PARSED/TRUNCATED，超限/坏文件=FAILED+人话 parse_error（不抛出——登记成功
     *  解析失败是可展示状态，PENDING 行可重试）。
     *  R14-F14-01/04/07 修（2026-10-14）：按 id 走显式列回写（markParsing/
     *  finishParseSuccess/finishParseFailure），全程不 updateById 整实体——复活行的
     *  del_flag='1' 快照不再被写回（自毁），读取间隙被移除的行不再被快照写活，
     *  HTTP 线程返回的实体与解析线程彻底解耦。 */
    private void parse(Long id, SysOssVo oss) {
        if (attachmentMapper.markParsing(id) == 0) {
            return;
        }
        String status;
        String parsedText = null;
        int lineCount = 0;
        String parseError = null;
        try {
            // 089 修：超限话术分场景（"文件级对比"是对比功能的话术，勿串台）
            String text = textExtractor.extractUncapped(oss, MAX_BYTES, OVER_LIMIT_MSG);
            OssTextExtractor.CappedText capped =
                OssTextExtractor.capLines(text, ATTACHMENT_RESULT_MAX_LINES);
            parsedText = capped.text();
            lineCount = countLines(capped.text());
            status = capped.truncated() ? "TRUNCATED" : "PARSED";
        } catch (Exception e) {
            // 批I 零技术词：parseError 会经 chip 悬停/toast/登记回执三路直出用户面——
            // 英文原文（POI/IO 异常）只进日志，用户侧统一人话；已知文件类错误保留语义
            log.warn("[AssistantAttach] 附件解析失败 ossId={}: {}", oss.getOssId(), e.getMessage(), e);
            status = "FAILED";
            String msg = e.getMessage() == null ? "" : e.getMessage();
            boolean hasCjk = msg.matches(".*\\p{IsHan}.*");
            parseError = hasCjk ? msg : "文件解析失败：格式不支持或文件损坏，请换标准格式（docx/xlsx/pptx/pdf/txt）重试";
        }
        if ("FAILED".equals(status)) {
            attachmentMapper.finishParseFailure(id, parseError);
        } else {
            attachmentMapper.finishParseSuccess(id, status, parsedText, lineCount);
        }
    }

    /** 行数=换行数+1（空文本=0；TRUNCATED 提示展示用） */
    private static int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int n = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    private static String normalizeSuffix(String suffix) {
        String value = suffix == null ? "" : suffix.trim().toLowerCase();
        return value.startsWith(".") ? value.substring(1) : value;
    }
}
