WHENEVER SQLERROR EXIT SQL.SQLCODE
SET ECHO OFF
SET SERVEROUTPUT ON SIZE UNLIMITED
SET PAGESIZE 100
SET LINESIZE 220

CONNECT SYSTEM/DbStudio123@//localhost:1521/FREEPDB1
SET SERVEROUTPUT ON SIZE UNLIMITED

DECLARE
  table_count NUMBER;
  ledger_count NUMBER;
  type_count NUMBER;
BEGIN
  FOR schema_name IN (
    SELECT column_value AS owner
      FROM TABLE(SYS.ODCIVARCHAR2LIST('CBSAC', 'CBSCM', 'CBSLN'))
  ) LOOP
    SELECT COUNT(*) INTO table_count
      FROM ALL_TABLES
     WHERE OWNER = schema_name.owner
       AND NESTED = 'NO';
    IF table_count < 20 THEN
      RAISE_APPLICATION_ERROR(-20001, schema_name.owner || ' has only ' || table_count || ' tables');
    END IF;
    DBMS_OUTPUT.PUT_LINE(schema_name.owner || ': ' || table_count || ' tables');
  END LOOP;

  SELECT COUNT(*) INTO ledger_count FROM CBSAC.TXN_LEDGER;
  IF ledger_count <= 50000 THEN
    RAISE_APPLICATION_ERROR(-20002, 'CBSAC.TXN_LEDGER has only ' || ledger_count || ' rows');
  END IF;
  DBMS_OUTPUT.PUT_LINE('CBSAC.TXN_LEDGER: ' || ledger_count || ' rows');

  FOR required_type IN (
    SELECT column_value AS data_type
      FROM TABLE(SYS.ODCIVARCHAR2LIST(
        'NUMBER', 'FLOAT', 'BINARY_FLOAT', 'BINARY_DOUBLE',
        'CHAR', 'VARCHAR2', 'NCHAR', 'NVARCHAR2',
        'DATE', 'TIMESTAMP', 'TIMESTAMP WITH TIME ZONE', 'TIMESTAMP WITH LOCAL TIME ZONE',
        'INTERVAL YEAR TO MONTH', 'INTERVAL DAY TO SECOND',
        'RAW', 'ROWID', 'UROWID', 'CLOB', 'NCLOB', 'BLOB', 'BFILE', 'LONG', 'LONG RAW',
        'XMLTYPE', 'JSON', 'BOOLEAN', 'VECTOR', 'ANYDATA',
        'ADDRESS_OBJ', 'PHONE_VARRAY', 'TAG_NESTED_TABLE', 'REF'
      ))
  ) LOOP
    SELECT COUNT(*) INTO type_count
      FROM ALL_TAB_COLUMNS
     WHERE OWNER = 'CBSAC'
       AND (
         DATA_TYPE = required_type.data_type
         OR (required_type.data_type = 'TIMESTAMP'
             AND REGEXP_LIKE(DATA_TYPE, '^TIMESTAMP\([0-9]+\)$'))
         OR (required_type.data_type = 'TIMESTAMP WITH TIME ZONE'
             AND REGEXP_LIKE(DATA_TYPE, '^TIMESTAMP\([0-9]+\) WITH TIME ZONE$'))
         OR (required_type.data_type = 'TIMESTAMP WITH LOCAL TIME ZONE'
             AND REGEXP_LIKE(DATA_TYPE, '^TIMESTAMP\([0-9]+\) WITH LOCAL TIME ZONE$'))
         OR (required_type.data_type = 'INTERVAL YEAR TO MONTH'
             AND REGEXP_LIKE(DATA_TYPE, '^INTERVAL YEAR\([0-9]+\) TO MONTH$'))
         OR (required_type.data_type = 'INTERVAL DAY TO SECOND'
             AND REGEXP_LIKE(DATA_TYPE, '^INTERVAL DAY\([0-9]+\) TO SECOND\([0-9]+\)$'))
         OR (required_type.data_type = 'REF' AND DATA_TYPE_MOD = 'REF')
       );
    IF type_count = 0 THEN
      RAISE_APPLICATION_ERROR(-20003, 'Missing data type: ' || required_type.data_type);
    END IF;
  END LOOP;
  DBMS_OUTPUT.PUT_LINE('Oracle core, LOB, legacy, object-relational and 23ai type coverage verified.');
END;
/

COLUMN OWNER FORMAT A10
COLUMN OBJECT_TYPE FORMAT A22
SELECT OWNER, OBJECT_TYPE, COUNT(*) AS OBJECT_COUNT
  FROM ALL_OBJECTS
 WHERE OWNER IN ('CBSAC', 'CBSCM', 'CBSLN')
   AND SUBOBJECT_NAME IS NULL
 GROUP BY OWNER, OBJECT_TYPE
 ORDER BY OWNER, OBJECT_TYPE;

PROMPT DBStudio Oracle fixture verification completed successfully.
