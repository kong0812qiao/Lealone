/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.ddl;

import java.util.ArrayList;

import org.lealone.api.ErrorCode;
import org.lealone.common.exceptions.DbException;
import org.lealone.common.util.New;
import org.lealone.db.Database;
import org.lealone.db.DbObject;
import org.lealone.db.DbObjectType;
import org.lealone.db.LealoneDatabase;
import org.lealone.db.ServerSession;
import org.lealone.db.auth.Role;
import org.lealone.db.auth.User;
import org.lealone.db.schema.Schema;
import org.lealone.db.schema.SchemaObject;
import org.lealone.db.table.Table;
import org.lealone.db.table.TableType;
import org.lealone.sql.SQLStatement;

/**
 * This class represents the statement
 * DROP DATABASE
 * 
 * @author H2 Group
 * @author zhh
 */
public class DropDatabase extends DatabaseStatement {

    private final String dbName;
    private boolean ifExists;
    private boolean deleteFiles;

    public DropDatabase(ServerSession session, String dbName) {
        super(session);
        this.dbName = dbName;
    }

    @Override
    public int getType() {
        return SQLStatement.DROP_DATABASE;
    }

    public void setIfExists(boolean ifExists) {
        this.ifExists = ifExists;
    }

    public void setDeleteFiles(boolean b) {
        this.deleteFiles = b;
    }

    @Override
    public int update() {
        checkRight();
        if (LealoneDatabase.NAME.equalsIgnoreCase(dbName)) {
            throw DbException.get(ErrorCode.CANNOT_DROP_LEALONE_DATABASE);
        }
        Database db;
        synchronized (LealoneDatabase.getInstance().getLock(DbObjectType.DATABASE)) {
            db = LealoneDatabase.getInstance().getDatabase(dbName);
            if (db == null) {
                if (!ifExists)
                    throw DbException.get(ErrorCode.DATABASE_NOT_FOUND_1, dbName);
            } else {
                LealoneDatabase.getInstance().removeDatabaseObject(session, db);
                if (isTargetEndpoint(db)) {
                    dropAllObjects(db);
                    if (deleteFiles) {
                        session.getDatabase().setDeleteFilesOnDisconnect(true);
                    }
                }
            }
        }
        executeDatabaseStatement(db);
        return 0;
    }

    private void dropAllObjects(Database db) {
        db.lockMeta(session);
        db.cleanPreparedStatements();
        // TODO local temp tables are not removed
        for (Schema schema : db.getAllSchemas()) {
            if (schema.canDrop()) {
                db.removeDatabaseObject(session, schema);
            }
        }
        ArrayList<Table> tables = db.getAllTablesAndViews(false);
        for (Table t : tables) {
            if (t.getName() != null && TableType.VIEW == t.getTableType()) {
                db.removeSchemaObject(session, t);
            }
        }
        for (Table t : tables) {
            if (t.getName() != null && TableType.STANDARD_TABLE == t.getTableType() && !t.isHidden()) {
                db.removeSchemaObject(session, t);
            }
        }
        session.findLocalTempTable(null);
        ArrayList<SchemaObject> list = New.arrayList();
        list.addAll(db.getAllSchemaObjects(DbObjectType.SEQUENCE));
        // maybe constraints and triggers on system tables will be allowed in
        // the future
        list.addAll(db.getAllSchemaObjects(DbObjectType.CONSTRAINT));
        list.addAll(db.getAllSchemaObjects(DbObjectType.TRIGGER));
        list.addAll(db.getAllSchemaObjects(DbObjectType.CONSTANT));
        list.addAll(db.getAllSchemaObjects(DbObjectType.FUNCTION_ALIAS));
        for (SchemaObject obj : list) {
            if (obj.isHidden()) {
                continue;
            }
            db.removeSchemaObject(session, obj);
        }
        for (User user : db.getAllUsers()) {
            if (user != session.getUser()) {
                db.removeDatabaseObject(session, user);
            }
        }
        for (Role role : db.getAllRoles()) {
            String sql = role.getCreateSQL();
            // the role PUBLIC must not be dropped
            if (sql != null) {
                db.removeDatabaseObject(session, role);
            }
        }
        ArrayList<DbObject> dbObjects = New.arrayList();
        dbObjects.addAll(db.getAllRights());
        dbObjects.addAll(db.getAllAggregates());
        dbObjects.addAll(db.getAllUserDataTypes());
        for (DbObject obj : dbObjects) {
            String sql = obj.getCreateSQL();
            // the role PUBLIC must not be dropped
            if (sql != null) {
                db.removeDatabaseObject(session, obj);
            }
        }
    }
}
