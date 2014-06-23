/*
 * Copyright (C) 2012 eXo Platform SAS.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.benjp.services.mongodb;

import com.mongodb.*;
import de.flapdoodle.embed.mongo.MongodExecutable;
import de.flapdoodle.embed.mongo.MongodProcess;
import de.flapdoodle.embed.mongo.MongodStarter;
import de.flapdoodle.embed.mongo.config.IMongodConfig;
import de.flapdoodle.embed.mongo.config.MongodConfigBuilder;
import de.flapdoodle.embed.mongo.config.Net;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.process.runtime.Network;
import org.benjp.utils.PropertyManager;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.logging.Logger;

public class MongoBootstrap
{
  private static MongodExecutable mongodExe;
  private static MongodProcess mongod;
  private Mongo m;
  private DB db;
  private static Logger log = Logger.getLogger("MongoBootstrap");

  private Mongo mongo()
  {
    if (m==null)
    {
      try
      {
        if (PropertyManager.PROPERTY_SERVER_TYPE_EMBED.equals(PropertyManager.getProperty(PropertyManager.PROPERTY_SERVER_TYPE)))
        {
          log.warning("WE WILL NOW USE MONGODB IN EMBED MODE...");
          log.warning("BE AWARE...");
          log.warning("EMBED MODE SHOULD NEVER BE USED IN PRODUCTION!");
          setupEmbedMongo();
        }

        MongoOptions options = new MongoOptions();
        options.connectionsPerHost = 200;
        options.connectTimeout = 60000;
        options.threadsAllowedToBlockForConnectionMultiplier = 10;
        options.autoConnectRetry = true;
        String host = PropertyManager.getProperty(PropertyManager.PROPERTY_SERVER_HOST);
        int port = Integer.parseInt(PropertyManager.getProperty(PropertyManager.PROPERTY_SERVER_PORT));
        m = new Mongo(new ServerAddress(host, port), options);
        m.setWriteConcern(WriteConcern.SAFE);
      }
      catch (UnknownHostException e)
      {
      }
      catch (IOException e)
      {
      }
    }
    return m;
  }

  public void close() {
    try {
      if (mongod != null) {
        mongod.stop();
        mongodExe.stop();
      }
      if (m!=null)
        m.close();
    } catch (NullPointerException e) {}
  }

  public void initialize() {
    this.close();
    this.m = null;
    this.mongo();
  }

  public void dropDB(String dbName)
  {
    log.info("---- Dropping DB " + dbName);
    mongo().dropDatabase(dbName);
    log.info("-------- DB " + dbName + " dropped!");

  }

  public DB getDB()
  {
    return getDB(null);
  }

  public DB getDB(String dbName)
  {
    if (db==null || dbName!=null)
    {
      if (dbName!=null)
        db = mongo().getDB(dbName);
      else
        db = mongo().getDB(PropertyManager.getProperty(PropertyManager.PROPERTY_DB_NAME));
      boolean authenticate = "true".equals(PropertyManager.getProperty(PropertyManager.PROPERTY_DB_AUTHENTICATION));
      if (authenticate)
      {
        db.authenticate(PropertyManager.getProperty(PropertyManager.PROPERTY_DB_USER), PropertyManager.getProperty(PropertyManager.PROPERTY_DB_PASSWORD).toCharArray());
      }
      initCollection("notifications");
      initCollection("room_rooms");
      initCollection("users");
      dropTokenCollectionIfExists();

    }
    return db;
  }

  private static void setupEmbedMongo() throws IOException {
    MongodStarter runtime = MongodStarter.getDefaultInstance();
    int port = Integer.parseInt(PropertyManager.getProperty(PropertyManager.PROPERTY_SERVER_PORT));
    IMongodConfig mongodConfig = new MongodConfigBuilder()
            .version(Version.Main.V2_6)
            .net(new Net(port, Network.localhostIsIPv6()))
            .build();

    mongodExe = runtime.prepare(mongodConfig);
    mongod = mongodExe.start();
  }

  public void initCappedCollection(String name, int size)
  {
    initCollection(name, true, size);
  }

  private void initCollection(String name)
  {
    initCollection(name, false, 0);
  }

  private void initCollection(String name, boolean isCapped, int size)
  {
    if (getDB().collectionExists(name)) return;

    BasicDBObject doc = new BasicDBObject();
    doc.put("capped", isCapped);
    if (isCapped)
      doc.put("size", size);
    getDB().createCollection(name, doc);

  }

  private void dropTokenCollectionIfExists()
  {
    if (getDB().collectionExists("tokens")) {
      DBCollection tokens = getDB().getCollection("tokens");
      tokens.drop();
    }
  }

  public void ensureIndexesInRoom(String roomId)
  {
    String dbName = this.getDB().getName();
    BasicDBObject unique = new BasicDBObject();
    unique.put("unique", true);
    unique.put("background", true);
    BasicDBObject notUnique = new BasicDBObject();
    notUnique.put("unique", false);
    notUnique.put("background", true);

    DBCollection collr = getDB().getCollection(ChatServiceImpl.M_ROOM_PREFIX+roomId);
    collr.ensureIndex(new BasicDBObject("timestamp", 1), notUnique.append("name", "timestamp_1").append("ns", dbName+".room_"+roomId));
    collr.ensureIndex(new BasicDBObject("timestamp", -1), notUnique.append("name", "timestamp_m1").append("ns", dbName+".room_"+roomId));
    log.info("##### room index in "+roomId);
  }

  public void ensureIndexes()
  {
    String dbName = this.getDB().getName();
    log.info("### ensureIndexes in "+dbName);
    BasicDBObject unique = new BasicDBObject();
    unique.put("unique", true);
    unique.put("background", true);
    BasicDBObject notUnique = new BasicDBObject();
    notUnique.put("unique", false);
    notUnique.put("background", true);

    DBCollection notifications = getDB().getCollection("notifications");
    notifications.dropIndexes();
    notifications.createIndex(new BasicDBObject("user", 1), notUnique.append("name", "user_1").append("ns", dbName+".notifications"));
    notifications.createIndex(new BasicDBObject("isRead", 1), notUnique.append("name", "isRead_1").append("ns", dbName+".notifications"));
    BasicDBObject index = new BasicDBObject();
    index.put("user", 1);
    index.put("categoryId", 1);
    index.put("category", 1);
    index.put("type", 1);
//    index.put("isRead", 1);
    notifications.createIndex(index, notUnique.append("name", "user_1_type_1_category_1_categoryId_1").append("ns", dbName+".notifications"));
    log.info("### notifications indexes in "+getDB().getName());

    DBCollection rooms = getDB().getCollection("room_rooms");
    rooms.dropIndexes();
    rooms.createIndex(new BasicDBObject("space", 1), notUnique.append("name", "space_1").append("ns", dbName+".room_rooms"));
    rooms.createIndex(new BasicDBObject("users", 1), notUnique.append("name", "users_1").append("ns", dbName+".room_rooms"));
    rooms.createIndex(new BasicDBObject("shortName", 1), notUnique.append("name", "shortName_1").append("ns", dbName+".room_rooms"));
    log.info("### rooms indexes in "+getDB().getName());

    DBCollection coll = getDB().getCollection(ChatServiceImpl.M_ROOM_PREFIX+ ChatServiceImpl.M_ROOMS_COLLECTION);
    DBCursor cursor = coll.find();
    while (cursor.hasNext())
    {
      DBObject dbo = cursor.next();
      String roomId = dbo.get("_id").toString();
      DBCollection collr = getDB().getCollection(ChatServiceImpl.M_ROOM_PREFIX+roomId);
      collr.ensureIndex(new BasicDBObject("timestamp", 1), notUnique.append("name", "timestamp_1").append("ns", dbName+".room_"+roomId));
      collr.ensureIndex(new BasicDBObject("timestamp", -1), notUnique.append("name", "timestamp_m1").append("ns", dbName+".room_"+roomId));
      log.info("##### room index in "+roomId);
    }


    DBCollection users = getDB().getCollection("users");
    users.dropIndexes();
    users.createIndex(new BasicDBObject("token", 1), notUnique.append("name", "token_1").append("ns", dbName + ".users"));
    users.createIndex(new BasicDBObject("validity", -1), notUnique.append("name", "validity_m1").append("ns", dbName + ".users"));
    index = new BasicDBObject();
    index.put("user", 1);
    index.put("token", 1);
    users.createIndex(index, unique.append("name", "user_1_token_1").append("ns", dbName + ".users"));
    index = new BasicDBObject();
    index.put("user", 1);
    index.put("validity", -1);
    users.createIndex(index, unique.append("name", "user_1_validity_m1").append("ns", dbName + ".users"));
    index = new BasicDBObject();
    index.put("validity", -1);
    index.put("isDemoUser", 1);
    users.createIndex(index, notUnique.append("name", "validity_1_isDemoUser_m1").append("ns", dbName + ".users"));

    users.createIndex(new BasicDBObject("user", 1), unique.append("name", "user_1").append("ns", dbName+".users"));
    users.createIndex(new BasicDBObject("spaces", 1), notUnique.append("name", "spaces_1").append("ns", dbName+".users"));
    log.info("### users indexes in "+getDB().getName());

    log.info("### Indexes creation completed in "+getDB().getName());

  }
}
