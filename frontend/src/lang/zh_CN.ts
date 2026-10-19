export default {
  // 路由国际化
  route: {
    dashboard: '首页',
    document: '项目文档'
  },
  // 登录页面国际化
  login: {
    welcome: '欢迎登录',
    tenant: '租户',
    tenantPlaceholder: '请选择租户',
    account: '账号',
    // 登录改造（2026-09-25 拍板 3A+ 市场主流版）：手机号/账号合一输入 + 先识别后登录
    phoneOrAccount: '手机号 / 账号',
    phoneOrAccountPlaceholder: '手机号或账号',
    accountNotFound: '未找到该账号的可登录单位，请联系管理员核实（账号不存在或所属单位已停用/过期）',
    specifyTenant: '指定单位登录',
    specifyTenantPlaceholder: '选择要登录的单位',
    pickTenantTitle: '选择要登录的单位',
    pickTenantHint: '该手机号/账号属于多个单位，请选择要进入的单位',
    otherWays: '其他登录方式',
    refreshCode: '点击刷新验证码',
    showPassword: '显示密码',
    hidePassword: '隐藏密码',
    selectPlaceholder: '请选择/输入公司名称',
    username: '用户名',
    password: '密码',
    login: '登 录',
    logging: '登 录 中...',
    code: '验证码',
    rememberPassword: '记住我',
    switchRegisterPage: '立即注册',
    // 批4 忘记密码（2026-09-26 拍板 8A）：邮箱验证码三步重置
    forgotPassword: '忘记密码？',
    forgotTitle: '重置密码',
    forgotStep1: '输入手机号或账号，系统将向该账号绑定的邮箱发送验证码（需先在「个人信息」中绑定邮箱）',
    forgotLoginId: '手机号 / 账号',
    forgotSendCode: '获取验证码',
    forgotPickTenant: '该账号属于多个单位，请选择要重置密码的单位',
    forgotCodeSent: '验证码已发送至 {email}',
    forgotEmailCode: '邮箱验证码',
    forgotNewPassword: '新密码',
    forgotConfirmPassword: '确认新密码',
    forgotSubmit: '重置密码',
    forgotSuccess: '密码重置成功，请使用新密码登录',
    // 协议勾选门禁(2026-09-07 合规批): 未勾选禁登录/注册, 协议页免登录可达
    agreementPrefix: '我已阅读并同意',
    userAgreement: '《用户协议》',
    agreementConj: '和',
    privacyPolicy: '《隐私政策》',
    agreementUncheckTip: '请先阅读并同意《用户协议》和《隐私政策》',
    legalLinks: '法律协议',
    rule: {
      tenantId: {
        required: '请输入您的租户编号'
      },
      username: {
        required: '请输入您的手机号或账号'
      },
      password: {
        required: '请输入您的密码',
        length: '密码长度必须介于 {min} 和 {max} 之间',
        pattern: '不能包含非法字符：{strings}'
      },
      confirmPassword: {
        required: '请再次输入您的密码',
        equalToPassword: '两次输入的密码不一致'
      },
      code: {
        required: '请输入验证码'
      }
    },
    social: {
      wechat: '微信登录',
      maxkey: 'MaxKey登录',
      topiam: 'TopIam登录',
      gitee: 'Gitee登录',
      github: 'Github登录'
    }
  },
  // 注册页面国际化
  register: {
    selectPlaceholder: '请选择/输入公司名称',
    username: '用户名',
    password: '密码',
    confirmPassword: '确认密码',
    register: '注 册',
    registering: '注 册 中...',
    registerSuccess: '恭喜你，您的账号 {username} 注册成功！',
    code: '验证码',
    switchLoginPage: '使用已有账户登录',
    // 批3 邀请注册（2026-09-26）：注册页只认有效邀请链接
    inviteOnly: '本系统仅支持邀请注册',
    inviteOnlyTip: '请向所在单位的管理员索取邀请链接，在电脑或手机浏览器中打开后即可注册。',
    joining: '邀请你加入',
    inviteRole: '注册后角色',
    phonenumber: '手机号（登录账号）',
    nickName: '姓名',
    email: '邮箱（选填）',
    rule: {
      tenantId: {
        required: '请输入您的租户编号'
      },
      username: {
        required: '请输入您的账号',
        length: '用户账号长度必须介于 {min} 和 {max} 之间'
      },
      phonenumber: {
        required: '请输入手机号',
        pattern: '请输入正确的11位手机号'
      },
      nickName: {
        required: '请输入姓名',
        length: '姓名长度不能超过30个字符'
      },
      email: {
        pattern: '请输入正确的邮箱地址'
      },
      password: {
        required: '请输入您的密码',
        length: '用户密码长度必须介于 {min} 和 {max} 之间',
        pattern: '不能包含非法字符：{strings}'
      },
      code: {
        required: '请输入验证码'
      },
      confirmPassword: {
        required: '请再次输入您的密码',
        equalToPassword: '两次输入的密码不一致'
      }
    }
  },
  // 导航栏国际化
  navbar: {
    full: '全屏',
    language: '语言',
    dashboard: '首页',
    document: '项目文档',
    message: '消息',
    layoutSize: '布局大小',
    selectTenant: '选择租户',
    layoutSetting: '布局设置',
    personalCenter: '个人中心',
    logout: '退出登录'
  }
};
