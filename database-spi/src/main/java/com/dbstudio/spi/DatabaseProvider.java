package com.dbstudio.spi;

import java.util.List;

/**
 * 数据库产品扩展点。
 *
 * <p>Provider 是无状态的服务定义，由 {@link java.util.ServiceLoader} 发现；连接、元数据和方言
 * 分别委托给对应适配器，核心编辑器不依赖具体数据库实现。</p>
 */
public interface DatabaseProvider {
    String id();

    String displayName();

    List<ConnectionField> connectionFields();

    DatabaseCapabilities capabilities();

    ConnectionAdapter connections();

    MetadataAdapter metadata();

    SqlDialect dialect();
}
