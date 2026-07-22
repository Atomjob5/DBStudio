package com.dbstudio.spi;

public enum DatabaseObjectType {
    CATALOG("数据库"),
    SCHEMA("架构"),
    TABLE("表"),
    VIEW("视图"),
    COLUMN("列"),
    INDEX("索引"),
    CONSTRAINT("约束"),
    TRIGGER("触发器"),
    PROCEDURE("存储过程"),
    FUNCTION("函数"),
    PACKAGE("包"),
    SEQUENCE("序列"),
    SYNONYM("同义词"),
    TYPE("类型");

    private final String displayName;

    DatabaseObjectType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
