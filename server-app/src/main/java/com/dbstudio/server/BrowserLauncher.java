package com.dbstudio.server;

import java.awt.Desktop;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/** 服务启动后打开本机浏览器；日志中只记录不含令牌的基础地址。 */
@Component
public final class BrowserLauncher implements ApplicationListener<WebServerInitializedEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(BrowserLauncher.class);
    private final LocalAccessToken token;
    private final boolean enabled;
    public BrowserLauncher(LocalAccessToken token, @Value("${dbstudio.open-browser:true}") boolean enabled) {
        this.token = token; this.enabled = enabled;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        if (!enabled) return;
        final String base = "http://127.0.0.1:" + event.getWebServer().getPort() + "/";
        final String url = token.enabled() ? base + "#token=" + token.launchValue() : base;
        Thread opener = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().browse(URI.create(url));
                        LOG.info("已请求打开本机浏览器 address={} tokenEnabled={}", base, token.enabled());
                    } else LOG.warn("无法自动打开浏览器，请在本机打开基础地址：{}", base);
                } catch (Exception exception) {
                    LOG.warn("无法自动打开浏览器，请在本机打开基础地址：{}", base, exception);
                }
            }
        }, "dbstudio-browser-launcher");
        opener.setDaemon(true);
        opener.start();
    }
}
