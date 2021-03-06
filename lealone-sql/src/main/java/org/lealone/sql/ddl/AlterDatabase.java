/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lealone.sql.ddl;

import java.util.Map;

import org.lealone.db.Database;
import org.lealone.db.DbObjectType;
import org.lealone.db.LealoneDatabase;
import org.lealone.db.RunMode;
import org.lealone.db.ServerSession;
import org.lealone.sql.SQLStatement;
import org.lealone.sql.router.RouterHolder;
import org.lealone.storage.Storage;

/**
 * This class represents the statement
 * ALTER DATABASE
 */
public class AlterDatabase extends DatabaseStatement {

    private final Database db;
    private final Map<String, String> parameters;
    private final Map<String, String> replicationProperties;
    private final RunMode runMode;

    public AlterDatabase(ServerSession session, Database db, Map<String, String> parameters,
            Map<String, String> replicationProperties, RunMode runMode) {
        super(session);
        this.db = db;
        this.parameters = parameters;
        this.replicationProperties = replicationProperties;
        this.runMode = runMode;
    }

    @Override
    public int getType() {
        return SQLStatement.ALTER_DATABASE;
    }

    @Override
    public int update() {
        checkRight();
        synchronized (LealoneDatabase.getInstance().getLock(DbObjectType.DATABASE)) {
            RunMode oldRunMode = db.getRunMode();
            if (runMode != null)
                db.setRunMode(runMode);
            if (parameters != null)
                ; // TODO
            if (replicationProperties != null)
                db.setReplicationProperties(replicationProperties);
            LealoneDatabase.getInstance().updateMeta(session, db);
            if (isTargetEndpoint(db)) {
                db.copy();
                if ((oldRunMode == RunMode.CLIENT_SERVER)
                        && (runMode == RunMode.REPLICATION || runMode == RunMode.SHARDING)) {
                    new Thread(() -> {
                        String[] hostIds = RouterHolder.getRouter().getHostIds(db, true);
                        for (Storage storage : db.getStorages()) {
                            storage.move(hostIds, runMode);
                        }
                    }, "Move Pages").start();
                }
            }
        }
        executeDatabaseStatement(db);
        return 0;
    }
}
