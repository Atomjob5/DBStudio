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
    sourcemap: false,
    // Monaco generates many large worker chunks. Their gzip estimates are not used by the packaged app.
    reportCompressedSize: false,
    // Keep the size signal useful without warning for the expected Monaco application bundle.
    chunkSizeWarningLimit: 5_000,
    rollupOptions: {
      onwarn(warning, warn) {
        if (warning.code === "INVALID_ANNOTATION" && warning.id?.includes("@vueuse/core")) return;
        warn(warning);
      }
    }
  }
});
