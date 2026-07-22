WHENEVER SQLERROR EXIT SQL.SQLCODE
SET ECHO ON
SET SERVEROUTPUT ON

CONNECT SYSTEM/DbStudio123@//localhost:1521/FREEPDB1

DECLARE
  PROCEDURE grant_to_peers(owner_name VARCHAR2, object_name VARCHAR2, privilege_name VARCHAR2) IS
  BEGIN
    FOR peer IN (
      SELECT column_value AS username
        FROM TABLE(SYS.ODCIVARCHAR2LIST('CBSAC', 'CBSCM', 'CBSLN'))
       WHERE column_value <> owner_name
    ) LOOP
      EXECUTE IMMEDIATE 'GRANT ' || privilege_name || ' ON ' || owner_name || '.' || object_name
        || ' TO ' || peer.username;
    END LOOP;
  END;
BEGIN
  FOR item IN (
    SELECT obj.OWNER, obj.OBJECT_NAME, obj.OBJECT_TYPE
      FROM ALL_OBJECTS obj
     WHERE obj.OWNER IN ('CBSAC', 'CBSCM', 'CBSLN')
       AND obj.OBJECT_TYPE IN ('TABLE', 'VIEW', 'SEQUENCE', 'PROCEDURE', 'FUNCTION', 'PACKAGE', 'TYPE')
       AND obj.SUBOBJECT_NAME IS NULL
       AND NOT EXISTS (
         SELECT 1
           FROM ALL_NESTED_TABLES nested_storage
          WHERE nested_storage.OWNER = obj.OWNER
            AND nested_storage.TABLE_NAME = obj.OBJECT_NAME
       )
  ) LOOP
    IF item.OBJECT_TYPE IN ('TABLE', 'VIEW', 'SEQUENCE') THEN
      grant_to_peers(item.OWNER, item.OBJECT_NAME, 'SELECT');
    ELSIF item.OBJECT_TYPE IN ('PROCEDURE', 'FUNCTION', 'PACKAGE', 'TYPE') THEN
      grant_to_peers(item.OWNER, item.OBJECT_NAME, 'EXECUTE');
    END IF;
  END LOOP;
END;
/

PROMPT Granted cross-schema read and execute access for object-tree testing.
