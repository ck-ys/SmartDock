package com.smartdock.risk.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.smartdock.risk.domain.DcsRiskEvent;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 风控事件 VO(Req 7.1/7.3): 在事件实体基础上补齐用户名, 前端不再看到裸 userId。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@AutoMapper(target = DcsRiskEvent.class)
public class DcsRiskEventVo extends DcsRiskEvent implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户账号(冗余, 来自 sys_user.user_name) */
    private String userName;

    /** 用户昵称(冗余, 来自 sys_user.nick_name), 优先展示 */
    private String nickName;
}
