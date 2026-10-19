package com.smartdock.fileguard;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.core.domain.model.LoginUser;
import com.smartdock.domain.DcsFileGuardLog;
import com.smartdock.domain.DcsFileReviewConfig;
import com.smartdock.enums.FileReviewCategory;
import com.smartdock.mapper.DcsFileGuardLogMapper;
import com.smartdock.service.IDcsFileReviewConfigService;
import org.dromara.system.service.FileGuardChecker;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * file-guard 检测器 SPI 实现（批二接线，计划 FILE_DETECTION_GUARD_PLAN_20260918）
 *
 * <p>判定核心在 {@link FileGuardDecision}（纯函数，批一）；本类负责三件接线事务：
 * ①从租户配置解析模式三档（OFF/OBSERVE/ENFORCE，Q5=B 界面可切，缺省 OBSERVE 只记日志）；
 * ②按深扫上限决定内容载入策略（≤上限整体载入供 L3；超限只读 32 字节头部，L1/L2 仍生效——
 * 500MB 上传上限放开后的内存必选闸，合同#8）；
 * ③按模式翻译判定结果——ENFORCE 下 DENY 抛 ServiceException（对象不落 MinIO）、
 * REVIEW 返回风险说明（经事件强制进审）；OBSERVE 下全部只记 WARN 日志（合同#6，一周误报率=grep 日志）。</p>
 *
 * <p>检测对全体用户一致（含超管/租管）：安全层保护的是下载者，与 12b 内容审核的管理员豁免
 * 是两回事（计划架构节拍板）。</p>
 *
 * @author smartdock
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DcsFileGuardCheckerImpl implements FileGuardChecker {

    /** 检测模式三档（配置列 guard_mode） */
    public static final String MODE_OFF = "OFF";
    public static final String MODE_OBSERVE = "OBSERVE";
    public static final String MODE_ENFORCE = "ENFORCE";

    /** L2 魔数比对所需的最小头部字节数 */
    private static final int HEAD_BYTES = 32;
    /** 深扫大小上限缺省值（MB）——配置列为空/非法时的兜底；config 服务与单测共用 */
    public static final int DEFAULT_DEEP_SCAN_MAX_MB = 50;

    private final IDcsFileReviewConfigService configService;
    private final DcsFileGuardLogMapper guardLogMapper;

    @Override
    public String checkUpload(MultipartFile file) {
        DcsFileReviewConfig cfg = configService.getEffective();
        String mode = resolveMode(cfg);
        if (MODE_OFF.equals(mode)) {
            return null;
        }
        long deepScanMaxBytes = resolveDeepScanMaxBytes(cfg);
        String fileName = file.getOriginalFilename();

        // 内容载入：≤上限整体读（供 L3 深扫，瞬时内存≤上限值）；超限只读头部
        final byte[] content;
        byte[] head;
        try {
            if (file.getSize() <= deepScanMaxBytes) {
                content = file.getBytes();
                head = content;
            } else {
                content = null;
                head = readHead(file);
            }
        } catch (IOException ex) {
            throw new ServiceException("文件读取失败，请重试");
        }

        FileGuardVerdict verdict = FileGuardDecision.decide(fileName, file.getSize(), head,
            resolveWhitelist(cfg), deepScanMaxBytes, ext -> deepScan(ext, content));

        switch (verdict.action()) {
            case DENY -> {
                logHit(mode, verdict, fileName, file.getSize());
                if (MODE_ENFORCE.equals(mode)) {
                    throw new ServiceException(verdict.userMessage());
                }
                return null;
            }
            case REVIEW -> {
                logHit(mode, verdict, fileName, file.getSize());
                return MODE_ENFORCE.equals(mode) ? verdict.userMessage() : null;
            }
            default -> {
                if (log.isDebugEnabled()) {
                    log.debug("[fileGuard] {} 放行 规则={} 文件={}", mode, verdict.rule(), fileName);
                }
                return null;
            }
        }
    }

    /** L3 路由：按扩展名分派到对应扫描器（content 非空的场景 decide 才会调用） */
    private Optional<RiskFinding> deepScan(String ext, byte[] content) {
        if (content == null) {
            return Optional.empty();
        }
        return switch (ext) {
            case "pdf" -> PdfJsScanner.scan(content);
            case "svg" -> SvgXssScanner.scan(content);
            case "docx", "xlsx", "pptx", "doc", "xls", "ppt" -> OfficeMacroScanner.scan(ext, new ByteArrayInputStream(content));
            default -> Optional.empty();
        };
    }

    /** 模式解析：空/非法值兜底 OBSERVE（只记日志，绝不意外拦截线上传文件） */
    private String resolveMode(DcsFileReviewConfig cfg) {
        String mode = cfg.getGuardMode();
        if (StrUtil.isBlank(mode)) {
            return MODE_OBSERVE;
        }
        return switch (mode.toUpperCase(Locale.ROOT)) {
            case MODE_OFF, MODE_ENFORCE -> mode.toUpperCase(Locale.ROOT);
            default -> MODE_OBSERVE;
        };
    }

    /** 白名单解析：空=FileReviewCategory 七类并集默认（单一事实源）；否则逗号串归一 */
    private Set<String> resolveWhitelist(DcsFileReviewConfig cfg) {
        String raw = cfg.getGuardWhitelist();
        if (StrUtil.isBlank(raw)) {
            return FileReviewCategory.whitelistedExtensions();
        }
        Set<String> parsed = StrUtil.split(raw, ',').stream()
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(s -> s.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        // 三审P2-2防御：", ,"这类"非空但拆分后为空"的存量脏串与空串同等视为默认白名单，
        // 避免空集合白名单静默拒掉全租户所有上传
        return parsed.isEmpty() ? FileReviewCategory.whitelistedExtensions() : parsed;
    }

    /** 深扫上限解析：空/非正值兜底 50MB */
    private long resolveDeepScanMaxBytes(DcsFileReviewConfig cfg) {
        Integer mb = cfg.getGuardDeepScanMaxMb();
        int value = (mb == null || mb <= 0) ? DEFAULT_DEEP_SCAN_MAX_MB : mb;
        return value * 1024L * 1024L;
    }

    /** 只读文件头 HEAD_BYTES 字节（超深扫上限的大文件用） */
    private byte[] readHead(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            byte[] head = new byte[HEAD_BYTES];
            int read = 0;
            while (read < head.length) {
                int n = in.read(head, read, head.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return read == head.length ? head : Arrays.copyOf(head, read);
        }
    }

    /** 命中日志 + 落库：观察期的误报清单即「检测记录」页签（Q4=A 改版，原 grep 日志口径保留） */
    private void logHit(String mode, FileGuardVerdict verdict, String fileName, long size) {
        log.warn("[fileGuard] {} 命中规则={} 用户={} 文件={} 大小={}MB 详情={",
            mode, verdict.rule(), safeUserId(), fileName, size / (1024 * 1024), verdict.userMessage());
        try {
            DcsFileGuardLog row = new DcsFileGuardLog();
            row.setUserId(safeUserIdLong());
            row.setUserName(safeUserName());
            row.setFileName(fileName);
            row.setFileSize(size);
            row.setRuleCode(verdict.rule());
            row.setDetail(verdict.userMessage());
            row.setMode(mode);
            row.setDisposition(resolveDisposition(mode, verdict.action()));
            guardLogMapper.insert(row);
        } catch (Exception ex) {
            // 底账落库失败不影响上传主流程（Q4 合同：检测记录是运营抓手，不是交易一致性环节）
            log.error("[fileGuard] 命中记录落库失败 文件={} 规则={}", fileName, verdict.rule(), ex);
        }
    }

    /** 处置结果翻译：ENFORCE 拒传=DENIED / ENFORCE 进审=REVIEWED / OBSERVE 命中留痕=OBSERVED */
    private String resolveDisposition(String mode, GuardAction action) {
        if (MODE_ENFORCE.equals(mode)) {
            return action == GuardAction.DENY ? "DENIED" : "REVIEWED";
        }
        return "OBSERVED";
    }

    /** 落库用的用户ID：无登录上下文（单测/内部调用）返回 null 不炸 */
    private Long safeUserIdLong() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception ex) {
            return null;
        }
    }

    /** 落库用的用户姓名：取不到昵称空串兜底（列表展示用） */
    private String safeUserName() {
        try {
            LoginUser loginUser = LoginHelper.getLoginUser();
            return loginUser != null ? StrUtil.nullToEmpty(loginUser.getNickname()) : "";
        } catch (Exception ex) {
            return "";
        }
    }

    /** 日志用的用户标识：无登录上下文（单测/内部调用）不炸，标 unknown */
    private String safeUserId() {
        try {
            return String.valueOf(LoginHelper.getUserId());
        } catch (Exception ex) {
            return "unknown";
        }
    }
}
