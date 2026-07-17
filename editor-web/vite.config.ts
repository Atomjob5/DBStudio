import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import Components from "unplugin-vue-components/vite";
import { ElementPlusResolver } from "unplugin-vue-components/resolvers";

export default defineConfig({
  base: "./",
  plugins: [
    vue(),
    Components({
      dts: false,
      resolvers: [ElementPlusResolver({ importStyle: "css" })]
    })
  ],
  build: {
    target: "safari16",
    outDir: "dist",
    emptyOutDir: true,
    sourcemap: false
  }
});
