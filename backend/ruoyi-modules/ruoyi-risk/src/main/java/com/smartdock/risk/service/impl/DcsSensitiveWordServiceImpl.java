package com.smartdock.risk.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.MapstructUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import com.smartdock.risk.domain.DcsSensitiveWord;
import com.smartdock.risk.domain.bo.DcsSensitiveWordBo;
import com.smartdock.risk.domain.bo.SensitiveWordBatchCommand;
import com.smartdock.risk.domain.vo.DcsSensitiveWordVo;
import com.smartdock.risk.domain.vo.SensitiveWordInspectionVo;
import com.smartdock.risk.domain.vo.SensitiveWordStatsVo;
import com.smartdock.risk.mapper.DcsSensitiveWordMapper;
import com.smartdock.risk.service.DcsSensitiveWordFilterService;
import com.smartdock.risk.service.IDcsSensitiveWordService;
import com.smartdock.risk.service.SensitiveWordInputPolicy;
import com.smartdock.risk.support.SensitiveWordMatcher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 敏感词配置 CRUD 实现(对应后台「风控-敏感词库」菜单)。
 *
 * <p><b>与 {@link com.smartdock.risk.service.DcsSensitiveWordFilterService} 的区别</b>:
 * 本类只做词库增删改查(管理员维护), FilterService 做实时匹配(用户发消息时调用)。</p>
 *
 * <p><b>多租户</b>: 查询时用 {@code TenantHelper.ignore} + 手动 {@code bo.setTenantId(currentTenantId())},
 * 因为平台级词(tenantId=000000)也要对租户可见。</p>
 *
 * <p><b>内置词保护</b>: {@code builtin=1} 的词禁止删除/禁用({@code BUILTIN} 常量)。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DcsSensitiveWordServiceImpl implements IDcsSensitiveWordService {

    private static final String BUILTIN = "1";

    /** 疑似过严阈值-最少命中数(拍板 2026-09-23 Q2): 低于此量不判, 防小样本误标 */
    static final long OVERSTRICT_MIN_HITS = 5;
    /** 疑似过严阈值-误报率分子倍数(拍板 2026-09-23 Q2): fpCnt*2>=hitCnt 即 >=50%, 整数运算避免浮点 */
    static final long OVERSTRICT_RATE_NUM = 2;

    /** 敏感词表读写 Mapper（词库 CRUD） */
    private final DcsSensitiveWordMapper wordMapper;
    /** 敏感词输入策略服务（创建/更新/删除前置校验，内置词保护） */
    private final SensitiveWordInputPolicy inputPolicy;
    /** 实时过滤服务(命中自测委托其规则加载, 与发送链路同一套词库含内置基线) */
    private final DcsSensitiveWordFilterService filterService;
    /** 风险事件 Mapper(收尾批 Q1: 近 7 天按词命中数=误报率分母) */
    private final com.smartdock.risk.mapper.DcsRiskEventMapper riskEventMapper;
    /** 按词误报反馈 Mapper(收尾批 Q1: 近 7 天误报数=误报率分子, U03 表) */
    private final com.smartdock.risk.mapper.DcsWordFpFeedbackMapper wordFpFeedbackMapper;

    @Override
    public TableDataInfo<DcsSensitiveWordVo> queryPageList(DcsSensitiveWordBo bo, PageQuery pageQuery) {
        bo.setTenantId(currentTenantId());
        Page<DcsSensitiveWordVo> page = TenantHelper.ignore(() -> wordMapper.selectVoPage(pageQuery.build(), buildWrapper(bo)));
        enrichFpStats(page.getRecords());
        return TableDataInfo.build(page);
    }

    /**
     * 词库列表补误报统计(收尾批 Q1+Q2, 2026-09-23): 每词带出近 7 天命中数(hitCnt,
     * 事件 hit_word 聚合)与误报数(fpCnt, U03 反馈表聚合), 并打"疑似过严"标。
     * 两聚合走当前租户上下文(租户拦截器改写 SQL); 平台词(000000)在管理员租户下对账。
     */
    private void enrichFpStats(List<DcsSensitiveWordVo> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        java.util.Date since = new java.util.Date(System.currentTimeMillis() - 7L * 24 * 3600 * 1000);
        java.util.Map<String, Long> hitByWord = new java.util.HashMap<>();
        for (java.util.Map<String, Object> row : riskEventMapper.hitCountByWord(since)) {
            hitByWord.put(String.valueOf(row.get("word")), ((Number) row.get("cnt")).longValue());
        }
        java.util.Map<String, Long> fpByWord = new java.util.HashMap<>();
        for (java.util.Map<String, Object> row : wordFpFeedbackMapper.fpCountByWord(since)) {
            fpByWord.put(String.valueOf(row.get("word")), ((Number) row.get("fpCnt")).longValue());
        }
        for (DcsSensitiveWordVo vo : rows) {
            long hit = hitByWord.getOrDefault(vo.getWordText(), 0L);
            long fp = fpByWord.getOrDefault(vo.getWordText(), 0L);
            vo.setHitCnt(hit);
            vo.setFpCnt(fp);
            vo.setOverstrict(isOverstrict(hit, fp));
        }
    }

    /** 疑似过严判据(静态纯函数便于单测): 命中>=5 次且误报率>=50% */
    static boolean isOverstrict(long hitCnt, long fpCnt) {
        return hitCnt >= OVERSTRICT_MIN_HITS && fpCnt * OVERSTRICT_RATE_NUM >= hitCnt;
    }

    @Override
    public DcsSensitiveWordVo queryById(Long wordId) {
        String tenantId = currentTenantId();
        DcsSensitiveWordVo vo = TenantHelper.ignore(() -> wordMapper.selectVoOne(new LambdaQueryWrapper<DcsSensitiveWord>()
            .eq(DcsSensitiveWord::getWordId, wordId)
            .eq(DcsSensitiveWord::getTenantId, tenantId)
            .eq(DcsSensitiveWord::getDelFlag, "0")));
        if (vo == null) {
            throw new ServiceException("敏感词不存在");
        }
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long insertByBo(DcsSensitiveWordBo bo) {
        inputPolicy.prepareCreate(bo, currentTenantId());
        DcsSensitiveWord entity = MapstructUtils.convert(bo, DcsSensitiveWord.class);
        TenantHelper.ignore(() -> {
            checkWordUnique(null, entity.getTenantId(), entity.getWordText());
            wordMapper.insert(entity);
        });
        return entity.getWordId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateByBo(DcsSensitiveWordBo bo) {
        String tenantId = currentTenantId();
        return TenantHelper.ignore(() -> {
            DcsSensitiveWord origin = wordMapper.selectOne(new LambdaQueryWrapper<DcsSensitiveWord>()
                .eq(DcsSensitiveWord::getWordId, bo.getWordId())
                .eq(DcsSensitiveWord::getTenantId, tenantId)
                .eq(DcsSensitiveWord::getDelFlag, "0"));
            if (origin == null) {
                throw new ServiceException("敏感词不存在");
            }
            inputPolicy.prepareUpdate(bo, tenantId, origin);
            if (BUILTIN.equals(origin.getBuiltin()) && !Objects.equals(origin.getWordText(), bo.getWordText())) {
                throw new ServiceException("内置敏感词不允许修改词条");
            }
            checkWordUnique(bo.getWordId(), bo.getTenantId(), bo.getWordText());
            DcsSensitiveWord patch = MapstructUtils.convert(bo, DcsSensitiveWord.class);
            return wordMapper.updateById(patch) > 0;
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return false;
        }
        String tenantId = currentTenantId();
        return TenantHelper.ignore(() -> {
            List<DcsSensitiveWord> rows = wordMapper.selectList(new LambdaQueryWrapper<DcsSensitiveWord>()
                .in(DcsSensitiveWord::getWordId, ids)
                .eq(DcsSensitiveWord::getTenantId, tenantId)
                .eq(DcsSensitiveWord::getDelFlag, "0"));
            if (rows.size() != ids.size()) {
                throw new ServiceException("敏感词不存在");
            }
            inputPolicy.assertDeletable(rows);
            return wordMapper.deleteByIds(rows.stream().map(DcsSensitiveWord::getWordId).toList()) > 0;
        });
    }

    @Override
    public SensitiveWordStatsVo stats() {
        String tenantId = currentTenantId();
        List<DcsSensitiveWord> rows = TenantHelper.ignore(() -> wordMapper.selectList(
            new LambdaQueryWrapper<DcsSensitiveWord>()
                .eq(DcsSensitiveWord::getTenantId, tenantId)
                .eq(DcsSensitiveWord::getDelFlag, "0")));
        SensitiveWordStatsVo vo = new SensitiveWordStatsVo();
        vo.setTotal(rows.size());
        rows.forEach(row -> {
            if ("1".equals(row.getEnabled())) vo.setEnabled(vo.getEnabled() + 1);
            if (SensitiveWordMatcher.ACTION_MASK.equals(row.getAction())) vo.setActionMask(vo.getActionMask() + 1);
            if (SensitiveWordMatcher.ACTION_BLOCK.equals(row.getAction())) vo.setActionBlock(vo.getActionBlock() + 1);
            if (SensitiveWordMatcher.ACTION_REVIEW.equals(row.getAction())) vo.setActionReview(vo.getActionReview() + 1);
        });
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String batchImport(SensitiveWordBatchCommand command) {
        String tenantId = currentTenantId();
        // 去空白 + 批内去重(保持导入顺序)
        List<String> words = command.words().stream()
            .map(StrUtil::trim).filter(StrUtil::isNotBlank).distinct().toList();
        if (words.isEmpty()) {
            throw new ServiceException("导入词条不能为空");
        }
        // 预查库内已有词(租户内未删除), 命中即跳过——批量导入不覆盖已有配置
        Set<String> existing = TenantHelper.ignore(() -> wordMapper.selectList(
                new LambdaQueryWrapper<DcsSensitiveWord>()
                    .eq(DcsSensitiveWord::getTenantId, tenantId)
                    .in(DcsSensitiveWord::getWordText, words)
                    .eq(DcsSensitiveWord::getDelFlag, "0")))
            .stream().map(DcsSensitiveWord::getWordText).collect(Collectors.toSet());
        int inserted = 0;
        int skipped = 0;
        for (String word : words) {
            if (existing.contains(word)) {
                skipped++;
                continue;
            }
            DcsSensitiveWordBo bo = new DcsSensitiveWordBo();
            bo.setWordText(word);
            bo.setCategory(command.category());
            bo.setAction(command.action());
            bo.setEnabled(command.enabled());
            bo.setRemark(command.remark());
            bo.setScene(command.scene());
            try {
                // 每词复用单条新增的校验与归一化(动作白名单/默认值/长度限制);
                // 非法词跳过计入 skipped 而非整批回滚(三审修正: 500 词里 1 个坏词不应毁掉全部)
                inputPolicy.prepareCreate(bo, tenantId);
                wordMapper.insert(MapstructUtils.convert(bo, DcsSensitiveWord.class));
                inserted++;
            } catch (DuplicateKeyException e) {
                // 并发导入撞唯一索引兜底(uk tenant+word_text WHERE del_flag='0'): 转跳过
                skipped++;
            } catch (ServiceException e) {
                log.warn("[SensitiveWord] batch-import skip invalid word tenant={} wordLen={} reason={}",
                    tenantId, word.length(), e.getMessage());
                skipped++;
            }
        }
        log.info("[SensitiveWord] batch-import tenant={} requested={} inserted={} skipped={}",
            tenantId, words.size(), inserted, skipped);
        return skipped == 0
            ? "成功导入 " + inserted + " 条"
            : "成功导入 " + inserted + " 条，跳过 " + skipped + " 条（重复或非法）";
    }

    @Override
    public SensitiveWordInspectionVo testMatch(String text, String scene) {
        String trimmed = StrUtil.trim(text);
        if (StrUtil.isBlank(trimmed)) {
            throw new ServiceException("请输入要测试的文本");
        }
        if (trimmed.length() > 2000) {
            throw new ServiceException("测试文本不能超过 2000 字");
        }
        // scene 空=全部词(与旧口径一致); 显式场景走 FilterService 场景装载(2026-09-22)
        SensitiveWordMatcher.InspectionResult result = filterService.inspect(trimmed, StrUtil.isBlank(scene) ? null : scene.trim().toUpperCase());
        SensitiveWordInspectionVo vo = new SensitiveWordInspectionVo();
        vo.setBlocked(result.blocked());
        vo.setReviewRequired(result.reviewRequired());
        vo.setHits(result.hits().stream().map(hit -> {
            SensitiveWordInspectionVo.Hit h = new SensitiveWordInspectionVo.Hit();
            h.setWord(hit.word());
            h.setAction(hit.action());
            h.setStart(hit.start());
            h.setEnd(hit.end());
            return h;
        }).toList());
        return vo;
    }

    private LambdaQueryWrapper<DcsSensitiveWord> buildWrapper(DcsSensitiveWordBo bo) {
        LambdaQueryWrapper<DcsSensitiveWord> w = new LambdaQueryWrapper<>();
        w.eq(DcsSensitiveWord::getTenantId, bo.getTenantId());
        w.like(StrUtil.isNotBlank(bo.getWordText()), DcsSensitiveWord::getWordText, bo.getWordText());
        w.eq(StrUtil.isNotBlank(bo.getCategory()), DcsSensitiveWord::getCategory, bo.getCategory());
        w.eq(StrUtil.isNotBlank(bo.getAction()), DcsSensitiveWord::getAction, bo.getAction());
        w.eq(StrUtil.isNotBlank(bo.getEnabled()), DcsSensitiveWord::getEnabled, bo.getEnabled());
        w.eq(StrUtil.isNotBlank(bo.getBuiltin()), DcsSensitiveWord::getBuiltin, bo.getBuiltin());
        // 场景筛选(CSV 包含匹配, 与 FilterService.loadRules 装载同口径, 2026-09-22)
        w.apply(StrUtil.isNotBlank(bo.getScene()), "scene LIKE '%' || {0} || '%'", bo.getScene());
        w.eq(DcsSensitiveWord::getDelFlag, "0");
        w.orderByAsc(DcsSensitiveWord::getCategory).orderByDesc(DcsSensitiveWord::getAction).orderByDesc(DcsSensitiveWord::getUpdateTime);
        return w;
    }

    private void checkWordUnique(Long wordId, String tenantId, String wordText) {
        List<DcsSensitiveWord> exists = wordMapper.selectList(new LambdaQueryWrapper<DcsSensitiveWord>()
            .eq(DcsSensitiveWord::getTenantId, tenantId)
            .eq(DcsSensitiveWord::getWordText, wordText)
            .eq(DcsSensitiveWord::getDelFlag, "0"));
        boolean duplicated = exists.stream().anyMatch(item -> !Objects.equals(item.getWordId(), wordId));
        if (duplicated) {
            throw new ServiceException("当前租户下敏感词已存在");
        }
    }

    private String currentTenantId() {
        return StrUtil.blankToDefault(LoginHelper.getTenantId(), "000000");
    }
}
