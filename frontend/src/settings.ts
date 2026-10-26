import { LanguageEnum } from '@/enums/LanguageEnum';
import { NavTypeEnum } from '@/enums/NavTypeEnum';

const setting: DefaultSettings = {
  /**
   * 网页标题
   */
  title: import.meta.env.VITE_APP_TITLE,

  theme: '#1d1f20',

  /**
   * 数据强调色（只给图表与数据标记，不给按钮/导航）
   * 双主题批（U-24）起实际渲染色由皮肤令牌 --app-accent/--app-chart-1 决定；
   * 此默认值仅随 layout-setting 初始对象落 localStorage，与 utils/theme.ts 的
   * DEFAULT_ACCENT 对齐，旧薄荷死配置已清
   */
  accent: '#2f6feb',

  /**
   * 侧边栏主题 深色主题theme-dark，浅色主题theme-light
   */
  sideTheme: 'theme-light',
  /**
   * 是否系统布局配置
   */
  showSettings: true,

  /**
   * 默认布局
   */
  navType: NavTypeEnum.LEFT,

  /**
   * 是否显示 tagsView
   */
  tagsView: true,

  /**
   * 显示页签图标
   */
  tagsIcon: false,

  /**
   * 是否固定头部
   */
  fixedHeader: true,

  /**
   * 是否显示logo
   */
  sidebarLogo: true,

  /**
   * 是否显示动态标题
   */
  dynamicTitle: false,

  /**
   * 是否开启动画 开启随机 关闭渐进渐出
   */
  animationEnable: false,

  /**
   * 是否暗黑模式
   */
  dark: false,

  /**
   * 默认语言
   */
  language: LanguageEnum.zh_CN,

  /**
   * 默认大小
   */
  size: 'default',

  /**
   * 默认布局
   */
  layout: '',

  /**
   * 页面圆角大小
   */
  radiusBase: 8
};
export default setting;
