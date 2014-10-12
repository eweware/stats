package com.eweware.stats;

import com.mongodb.*;
import com.eweware.DBException;

import java.util.ArrayList;
import java.util.List;

/**
 * @author rk@post.harvard.edu
 *         Date: 6/16/12 Time: 11:03 AM
 */
public class DBCollections {
    private static DBCollections singleton;

    /* Should read db and collection names from config file shared with REST service */
    private static final String SYSDB = "sysdb";
    private static final String USERDB = "userdb";
    private static final String BLAHDB = "blahdb";
    private static final String TRACKERDB = "trackerdb";
    private static final String BADGEDB = "badgedb";
    private static final String USERS_COLLECTION = "users";
    private static final String BADGE_AUTHORITIES_COLLECTION = "badgeauthorities";
    private static final String BADGE_APP_COLLECTION = "app";
    private static final String BADGE_TRANSACTIONS_COLLECTION = "tx";
    private static final String USER_ACCOUNTS_COLLECTION = "useraccounts";
    private static final String USERPROFILES_COLLECTION = "userprofiles";
    private static final String GROUPS_COLLECTION = "groups";
    private static final String GROUP_TYPES_COLLECTION = "groupTypes";
    private static final String USERGROUPS_COLLECTION = "usergroups";
    private static final String USER_BLAH_INFO_COLLECTION = "userBlahInfo";
    private static final String USER_COMMENT_INFO_COLLECTION = "userCommentInfo";
    private static final String BLAHS_COLLECTION = "blahs";
    private static final String BLAH_TYPES_COLLECTION = "blahTypes";
    private static final String COMMENTS_COLLECTION = "comments";
    private static final String INBOXSTATE_COLLECTION = "inboxstate";
    private static final String BLAHINBOX_COLLECTION = "blahinbox";
    private static final String TRACKBLAH_COLLECTION = "trackblah";
    private static final String TRACKCOMMENT_COLLECTION = "trackcomment";
    private static final String TRACKUSER_COLLECTION = "trackuser";
    private static final String TRACKER_COLLECTION = "tracker";
    private static final String DEMOGRAPHICS_COLLECTION = "demographics";
    private static final String MEDIA_COLLECTION = "media";

    private final MongoClient mongoClient;

    public final DB sysdb;
    public final DB userdb;
    public final DB blahdb;
    public final DB trackerdb;
    public final DB badgedb;
    private final DBCollection usersCol;
    private final DBCollection badgeAuthoritiesCol;
    private final DBCollection badgeAppCol;
    private final DBCollection badgeTransactionsCol;
    private final DBCollection userAccountsCol;
    private final DBCollection userProfilesCol;
    private final DBCollection groupsCol;
    private final DBCollection groupTypesCol;
    private final DBCollection userGroupsCol;
    private final DBCollection inboxStateCol;
    private final DBCollection blahsCol;
    private final DBCollection blahTypesCol;
    private final DBCollection commentsCol;
    private final DBCollection blahInboxCol;
    private final DBCollection trackBlahCol;
    private final DBCollection trackCommentCol;
    private final DBCollection trackUserCol;
    private final DBCollection trackerCol;
    private final DBCollection userBlahInfoCol;
    private final DBCollection userCommentInfoCol;


    public MongoClient getMongoClient() {
        return mongoClient;
    }

    public DBCollection getUsersCol() {
        return usersCol;
    }

    public DBCollection getBadgeAuthoritiesCol() {
        return badgeAuthoritiesCol;
    }

    public DBCollection getBadgeAppCol() {
        return badgeAppCol;
    }

    public DBCollection getBadgeTransactionsCol() {
        return badgeTransactionsCol;
    }

    public DBCollection getUserAccountsCol() {
        return userAccountsCol;
    }

    public DBCollection getUserProfilesCol() {
        return userProfilesCol;
    }

    public DBCollection getGroupsCol() {
        return groupsCol;
    }

    public DBCollection getGroupTypesCol() {
        return groupTypesCol;
    }

    public DBCollection getUserGroupsCol() {
        return userGroupsCol;
    }

    public DBCollection getInboxStateCol() {
        return inboxStateCol;
    }

    public DBCollection getBlahsCol() {
        return blahsCol;
    }

    public DBCollection getBlahTypesCol() {
        return blahTypesCol;
    }

    public DBCollection getCommentsCol() {
        return commentsCol;
    }

    public DBCollection getBlahInboxCol() {
        return blahInboxCol;
    }

    public DBCollection getTrackBlahCol() {
        return trackBlahCol;
    }

    public DBCollection getTrackCommentCol() {
        return trackCommentCol;
    }

    public DBCollection getTrackUserCol() {
        return trackUserCol;
    }

    public DBCollection getTrackerCol() {
        return trackerCol;
    }

    public DBCollection getUserBlahInfoCol() {
        return userBlahInfoCol;
    }

    public DBCollection getUserCommentInfoCol() {
        return userCommentInfoCol;
    }

    public DB getDB(String dbName) {
        return mongoClient.getDB(dbName);
    }

    public static DBCollections getInstance() throws DBException {
        if (DBCollections.singleton == null) {
            DBCollections.singleton = new DBCollections(Main.getDbHostnames(), Main.get_dbPort());
        }
        return DBCollections.singleton;
    }

    public DBCollection getCollection(String dbName, String collectionName) {
        return mongoClient.getDB(dbName).getCollection(collectionName);
    }

    private DBCollections(List<String> hostnames, int port) throws DBException {

        try {
            List<ServerAddress> serverAddresses = new ArrayList<ServerAddress>();
            for (String hostname : hostnames) {
                serverAddresses.add(new ServerAddress(hostname, port));
            }
            final MongoClientOptions.Builder builder = new MongoClientOptions.Builder();
            if (hostnames.size() > 1) { // replica set
                builder
                        .readPreference(ReadPreference.secondaryPreferred()) // tries to read from primary
                        .writeConcern(WriteConcern.MAJORITY);      // Writes to secondaries before returning
            }
            builder.connectionsPerHost(2).connectTimeout(10 * 60 * 1000); //  minute connection timeout
            this.mongoClient = new MongoClient(serverAddresses, builder.build());

            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    if (mongoClient != null) {
                        mongoClient.close();
                        System.out.println("Shut down MongoDB client");
                    }
                }
            }));

            // TODO we're getting db and collection names in the blind here.

            sysdb = mongoClient.getDB(SYSDB);
            userdb = mongoClient.getDB(USERDB);
            blahdb = mongoClient.getDB(BLAHDB);
            trackerdb = mongoClient.getDB(TRACKERDB);
            badgedb = mongoClient.getDB(BADGEDB);

            usersCol = userdb.getCollection(USERS_COLLECTION);
            badgeAuthoritiesCol = userdb.getCollection(BADGE_AUTHORITIES_COLLECTION);
            badgeAppCol = badgedb.getCollection(BADGE_APP_COLLECTION);
            badgeTransactionsCol = badgedb.getCollection(BADGE_TRANSACTIONS_COLLECTION);
            userAccountsCol = userdb.getCollection(USER_ACCOUNTS_COLLECTION);
            userProfilesCol = userdb.getCollection(USERPROFILES_COLLECTION);
            groupsCol = userdb.getCollection(GROUPS_COLLECTION);
            groupTypesCol = userdb.getCollection(GROUP_TYPES_COLLECTION);
            userGroupsCol = userdb.getCollection(USERGROUPS_COLLECTION);
            userBlahInfoCol = userdb.getCollection(USER_BLAH_INFO_COLLECTION);
            userCommentInfoCol = userdb.getCollection(USER_COMMENT_INFO_COLLECTION);

            blahsCol = blahdb.getCollection(BLAHS_COLLECTION);
            blahTypesCol = blahdb.getCollection(BLAH_TYPES_COLLECTION);
            commentsCol = blahdb.getCollection(COMMENTS_COLLECTION);
            inboxStateCol = blahdb.getCollection(INBOXSTATE_COLLECTION);
            blahInboxCol = blahdb.getCollection(BLAHINBOX_COLLECTION);

            trackBlahCol = trackerdb.getCollection(TRACKBLAH_COLLECTION);
            trackCommentCol = trackerdb.getCollection(TRACKCOMMENT_COLLECTION);
            trackUserCol = trackerdb.getCollection(TRACKUSER_COLLECTION);
            trackerCol = trackerdb.getCollection(TRACKER_COLLECTION);
        } catch (Exception e) {
            throw new DBException("Failed to initialize mongo", e);
        }

    }
}
