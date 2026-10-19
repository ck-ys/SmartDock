package org.dromara.common.mybatis.core.page;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.core.utils.sql.SqlUtil;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 分页查询实体类
 *
 * <p>封装 MyBatis-Plus {@link Page} 的构建逻辑: 前端只传 pageNum/pageSize/orderByColumn/isAsc,
 * {@link #build()} 产出带排序的 {@code Page<T>} 供 Mapper 分页查询用。</p>
 *
 * <p><b>排序 DSL</b>: orderByColumn 支持逗号分隔多字段, isAsc 支持逗号分隔对应方向,
 * 字段名自动驼峰转下划线, 经 {@link SqlUtil#escapeOrderBySql} 白名单防注入。</p>
 *
 * @author Lion Li
 */
@Data
public class PageQuery implements Serializable {
    // ===== DCS 调用指南 =====
    // DCS 引用频次: 60+ 个文件
    // 典型用法: DCS 所有分页查询(mission/profile/score/review)用它构建 Page 对象
    // ⚠️ DCS 踩过的坑:
    //   - orderByColumn/isAsc 只对 MP 自动分页(selectVoPage/IPage)生效
    //   - DCS 自定义 SQL(missionList/groupedList/todoList)需手动传 orderBySql + 白名单防注入
    //     见 DcsMissionServiceImpl.buildQueryWrapper / DcsTraceServiceImpl.listMyTodo
    //   - 前端传排序字段不要加 priority 前缀, 否则 SQL 注入白名单匹配不上
    // 安全边界: escapeOrderBySql 拦截非字母/数字/下划线/逗号的字符(防 SQL 注入)
    // 扩展点: 如需复杂排序(计算列/表达式), 不要走 PageQuery, 自行在 Mapper XML 拼 ORDER BY
    // ===== DCS 调用指南 结束 =====

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 分页大小
     */
    private Integer pageSize;

    /**
     * 当前页数
     */
    private Integer pageNum;

    /**
     * 排序列
     */
    private String orderByColumn;

    /**
     * 排序的方向desc或者asc
     */
    private String isAsc;

    /**
     * 当前记录起始索引 默认值
     */
    public static final int DEFAULT_PAGE_NUM = 1;

    /**
     * 每页显示记录数 默认值 默认查全部
     */
    public static final int DEFAULT_PAGE_SIZE = Integer.MAX_VALUE;

    /**
     * 构建分页对象
     */
    public <T> Page<T> build() {
        Integer pageNum = ObjectUtil.defaultIfNull(getPageNum(), DEFAULT_PAGE_NUM);
        Integer pageSize = ObjectUtil.defaultIfNull(getPageSize(), DEFAULT_PAGE_SIZE);
        if (pageNum <= 0) {
            pageNum = DEFAULT_PAGE_NUM;
        }
        Page<T> page = new Page<>(pageNum, pageSize);
        List<OrderItem> orderItems = buildOrderItem();
        if (CollUtil.isNotEmpty(orderItems)) {
            page.addOrder(orderItems);
        }
        return page;
    }

    /**
     * 构建排序
     *
     * 支持的用法如下:
     * {isAsc:"asc",orderByColumn:"id"} order by id asc
     * {isAsc:"asc",orderByColumn:"id,createTime"} order by id asc,create_time asc
     * {isAsc:"desc",orderByColumn:"id,createTime"} order by id desc,create_time desc
     * {isAsc:"asc,desc",orderByColumn:"id,createTime"} order by id asc,create_time desc
     */
    private List<OrderItem> buildOrderItem() {
        if (StringUtils.isBlank(orderByColumn) || StringUtils.isBlank(isAsc)) {
            return null;
        }
        String orderBy = SqlUtil.escapeOrderBySql(orderByColumn);
        orderBy = StringUtils.toUnderScoreCase(orderBy);

        // 兼容前端排序类型
        isAsc = StringUtils.replaceEach(isAsc, new String[]{"ascending", "descending"}, new String[]{"asc", "desc"});

        String[] orderByArr = orderBy.split(StringUtils.SEPARATOR);
        String[] isAscArr = isAsc.split(StringUtils.SEPARATOR);
        if (isAscArr.length != 1 && isAscArr.length != orderByArr.length) {
            throw new ServiceException("排序参数有误");
        }

        List<OrderItem> list = new ArrayList<>();
        // 每个字段各自排序
        for (int i = 0; i < orderByArr.length; i++) {
            String orderByStr = orderByArr[i];
            String isAscStr = isAscArr.length == 1 ? isAscArr[0] : isAscArr[i];
            if ("asc".equals(isAscStr)) {
                list.add(OrderItem.asc(orderByStr));
            } else if ("desc".equals(isAscStr)) {
                list.add(OrderItem.desc(orderByStr));
            } else {
                throw new ServiceException("排序参数有误");
            }
        }
        return list;
    }

    /**
     * 当前页第一条记录的偏移量(LIMIT offset 用, 仅在某些手写分页场景需要)。
     *
     * @return (pageNum - 1) * pageSize; pageNum/pageSize 为 null 时会 NPE, 调用方需保证非空
     */
    @JsonIgnore
    public Integer getFirstNum() {
        return (pageNum - 1) * pageSize;
    }

    /** 便捷构造: 直接指定 pageSize + pageNum(排序字段需后续 setter 设置) */
    public PageQuery(Integer pageSize, Integer pageNum) {
        this.pageSize = pageSize;
        this.pageNum = pageNum;
    }

}
