import { createApp } from "vue";
import { createPinia } from "pinia";
import "element-plus/theme-chalk/dark/css-vars.css";
import "element-plus/theme-chalk/el-loading.css";
import "element-plus/theme-chalk/el-message.css";
import "element-plus/theme-chalk/el-message-box.css";
import "element-plus/theme-chalk/el-notification.css";
import App from "./App.vue";
import "./motion-tokens.css";
import "./style.css";

createApp(App).use(createPinia()).mount("#app");
