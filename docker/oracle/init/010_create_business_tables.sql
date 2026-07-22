WHENEVER SQLERROR EXIT SQL.SQLCODE
SET ECHO ON
SET SERVEROUTPUT ON
ALTER SESSION SET CONTAINER=FREEPDB1;

DECLARE
  TYPE name_list IS TABLE OF VARCHAR2(30);
  table_names name_list := name_list(
    'APP_CONFIG', 'ORGANIZATIONS', 'DEPARTMENTS', 'EMPLOYEES', 'CUSTOMERS',
    'SUPPLIERS', 'PRODUCTS', 'WAREHOUSES', 'INVENTORY', 'CONTRACTS',
    'SALES_ORDERS', 'SALES_ORDER_ITEMS', 'INVOICES', 'PAYMENTS', 'SHIPMENTS',
    'WORKFLOWS', 'TASKS', 'DOCUMENTS', 'NOTIFICATIONS', 'AUDIT_LOGS', 'TXN_LEDGER'
  );

  PROCEDURE create_tables(owner_name VARCHAR2) IS
    row_target PLS_INTEGER;
    insert_sql VARCHAR2(32767);
  BEGIN
    FOR i IN 1 .. table_names.COUNT LOOP
      EXECUTE IMMEDIATE
        'CREATE TABLE ' || owner_name || '.' || table_names(i) || ' ('
        || 'ID NUMBER(18) NOT NULL, '
        || 'BIZ_CODE VARCHAR2(64 CHAR) NOT NULL, '
        || 'DISPLAY_NAME NVARCHAR2(128), '
        || 'STATUS CHAR(1 CHAR) DEFAULT ''A'' NOT NULL, '
        || 'AMOUNT NUMBER(18,2), '
        || 'CREATED_AT TIMESTAMP(6) DEFAULT SYSTIMESTAMP NOT NULL, '
        || 'PAYLOAD CLOB, '
        || 'CONSTRAINT ' || table_names(i) || '_PK PRIMARY KEY (ID), '
        || 'CONSTRAINT ' || table_names(i) || '_UK UNIQUE (BIZ_CODE))';

      EXECUTE IMMEDIATE 'COMMENT ON TABLE ' || owner_name || '.' || table_names(i)
        || ' IS ''DBStudio Oracle测试表：' || table_names(i) || '''';
      EXECUTE IMMEDIATE 'COMMENT ON COLUMN ' || owner_name || '.' || table_names(i)
        || '.BIZ_CODE IS ''业务唯一编码''';
      EXECUTE IMMEDIATE 'COMMENT ON COLUMN ' || owner_name || '.' || table_names(i)
        || '.DISPLAY_NAME IS ''中文显示名称''';

      row_target := CASE
        WHEN owner_name = 'CBSAC' AND table_names(i) = 'TXN_LEDGER' THEN 60000
        ELSE 250
      END;
      insert_sql := 'INSERT /*+ APPEND */ INTO ' || owner_name || '.' || table_names(i)
        || ' (ID,BIZ_CODE,DISPLAY_NAME,STATUS,AMOUNT,CREATED_AT,PAYLOAD) '
        || 'SELECT LEVEL, :table_name || ''-'' || LPAD(LEVEL,8,''0''), '
        || ':display_prefix || TO_CHAR(LEVEL), '
        || 'CASE MOD(LEVEL,4) WHEN 0 THEN ''I'' ELSE ''A'' END, '
        || 'ROUND(LEVEL * 1.27,2), SYSTIMESTAMP - NUMTODSINTERVAL(LEVEL,''MINUTE''), '
        || 'TO_CLOB(''{"source":"oracle-docker","row":'') || TO_CHAR(LEVEL) || ''}'' '
        || 'FROM DUAL CONNECT BY LEVEL <= ' || row_target;
      EXECUTE IMMEDIATE insert_sql USING table_names(i), '测试数据 ';
    END LOOP;
  END;
BEGIN
  create_tables('CBSAC');
  create_tables('CBSCM');
  create_tables('CBSLN');
  COMMIT;
END;
/

PROMPT Created 21 populated business tables in each schema.
