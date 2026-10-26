import { createApp } from 'vue';
// global css
import 'virtual:uno.css';
import 'element-plus/theme-chalk/dark/css-vars.css';
import '@/assets/styles/index.scss';

// App、router、store
import App from './App.vue';
import store from './store';
import router from './router';

// 皮肤（U-24 双主题）：样式表按 html[data-skin] 选 4 套令牌之一。
// 必须在 createApp/mount 之前、首帧之前同步执行——晚了会先按默认 T1 渲染再跳所选皮肤（闪变）
import { applyInitialSkin } from '@/utils/theme';
applyInitialSkin();

// 自定义指令
import directive from './directive';

// 注册插件
import plugins from './plugins/index'; // plugins

// 高亮组件
// import 'highlight.js/styles/a11y-light.css';
import 'highlight.js/styles/atom-one-dark.css';
import 'highlight.js/lib/common';
import HighLight from '@highlightjs/vue-plugin';

// svg图标
import 'virtual:svg-icons-register';
import ElementIcons from '@/plugins/svgicon';

// permission control
import './permission';

// 国际化
import i18n from '@/lang/index';

// vxeTable
import VXETable from 'vxe-table';
import 'vxe-table/lib/style.css';
VXETable.setConfig({
  zIndex: 999999
});

// 修改 el-dialog 默认点击遮照为不关闭
import { ElDialog } from 'element-plus';
ElDialog.props.closeOnClickModal.default = false;

const app = createApp(App);

app.use(HighLight);
app.use(ElementIcons);
app.use(router);
app.use(store);
app.use(i18n);
app.use(VXETable);
app.use(plugins);
// 自定义指令
directive(app);

app.mount('#app');
