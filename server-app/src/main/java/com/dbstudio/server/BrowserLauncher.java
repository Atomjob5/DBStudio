package com.dbstudio.server;

import java.awt.Desktop;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

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
        final String url = "http://127.0.0.1:" + event.getWebServer().getPort() + "/#token=" + token.launchValue();
        Thread opener = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url));
                    else LOG.warn("无法自动打开浏览器，请在本机打开：{}", url);
                } catch (Exception exception) {
                    LOG.warn("无法自动打开浏览器，请在本机打开：{}", url);
                }
            }
        }, "dbstudio-browser-launcher");
        opener.setDaemon(true);
        opener.start();
    }
}
