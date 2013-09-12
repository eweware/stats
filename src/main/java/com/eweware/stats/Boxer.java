package main.java.com.eweware.stats;

import com.mongodb.*;
import main.java.com.eweware.DBException;
import main.java.com.eweware.service.base.CommonUtilities;
import main.java.com.eweware.service.base.cache.BlahCache;
import main.java.com.eweware.service.base.error.SystemErrorException;
import main.java.com.eweware.service.base.store.dao.*;
import main.java.com.eweware.service.base.store.dao.schema.type.UserProfilePermissions;
import main.java.com.eweware.service.base.store.impl.mongo.dao.MongoStoreManager;
import main.java.com.eweware.stats.help.Utilities;
import org.bson.types.ObjectId;

import java.util.*;

/**
 * @author rk@post.harvard.edu
 *         Date: 4/21/13 Time: 3:57 PM
 */
public class Boxer {

    private int startingDaysBeforeToday;


    private DBCollection inboxStateCol = null;
    private DBCollection inboxCol = null;
    private DBCollection blahsCol = null;
    private DBCollection groupsCol = null;

    // number of inbox items deleted
    private long deletedCount = 0;

    // absolute maximum number of inboxes per group
    private int absoluteMaxInboxesPerGroup = 10;

    // desirable number of blahs per inbox
    private int desirableBlahsPerInbox = 100;

    // number old blahs per inbox
    private int desiredNumberOldBlahsPerInbox = 5;

    // minimum user strength considered "strong"
    private double minUserStrength = .80;


    // Each inbox contains the following mix of blahs:
    // from the last D days (recent)
    private double percentFromLastDdays = .20;
    // blahs in the 90 percentile blah strength from the last D days (recent)
    private double fractionOver90strength = .30;
    // blahs in the 75 percentile blah strength from the last D days (recent)
    private double fractionOver75strength = .30;
    // paid blahs
    private double fractionPaid = .10;
    // from before last D days but within M months ("old") whose blah strength is in 90 percentile
    private double fractionOlder = .10;

    private long totalNumberOfBlahsProcessed = 0;

    public Boxer(int startingDaysBeforeToday) throws DBException {
        this.startingDaysBeforeToday = startingDaysBeforeToday;
        inboxStateCol = DBCollections.getInstance().getInboxStateCol();
        inboxCol = DBCollections.getInstance().getBlahInboxCol();
        blahsCol = DBCollections.getInstance().getBlahsCol();
        groupsCol = DBCollections.getInstance().getGroupsCol();
    }

    public long execute() throws DBException, SystemErrorException, InterruptedException {
        final DBCursor cursor = Utilities.findInDB(3, "finding all group records", groupsCol, null, null);
        for (DBObject group : cursor) {
            long deletedInGroup = buildInbox(group.get(BaseDAOConstants.ID).toString());
            deletedCount += deletedInGroup;
        }
        Utilities.printit(true, "Deleted total " + deletedCount + " inbox state objects");
        return totalNumberOfBlahsProcessed;
    }

    private String getGroupName(String groupId) throws SystemErrorException, DBException, InterruptedException {
        final DBObject group = Utilities.findOneInDB(3, "finding a group record", groupsCol, new BasicDBObject(BaseDAOConstants.ID, MongoStoreManager.makeObjectId(groupId)), null);
        return (group == null) ? "Unknown Group" : ((String) group.get(GroupDAOConstants.DISPLAY_NAME));
    }

    /**
     * <p>Builds inboxes for the specified group.</p>
     * @param groupId   The group id
     * @return  The number of inbox state objects that were deleted
     * @throws SystemErrorException
     * @throws DBException
     */
    private long buildInbox(String groupId) throws SystemErrorException, DBException, InterruptedException {

        long deletedStateObjectsInGroup = 0;

        // If there are no blahs, there's nothing to do
        final Long blahsInGroupCount = Utilities.getCountFromDB(3, "get blah count for group", blahsCol, new BasicDBObject(BlahDAOConstants.GROUP_ID, groupId));
        if (blahsInGroupCount == 0) {
            System.err.println("No blahs in group '" + getGroupName(groupId) + "' id '" + groupId + "'");
            return deletedStateObjectsInGroup;
        }
        totalNumberOfBlahsProcessed += blahsInGroupCount;

        final Date initialCutoffDate = makeDateDaysBeforeToday(startingDaysBeforeToday);
          final Integer maxInboxNumberForGroup = Utilities.getValueAsInteger(Math.ceil((blahsInGroupCount * 1.0d) / (desirableBlahsPerInbox * 1.0d)), 0);


        final List<DBObject>  blahs = getAllBlahs(groupId);
        if (blahs.size() == 0) {
            System.err.println("No blahs in group '"+getGroupName(groupId)+"' id '" + groupId + "'");
            return deletedStateObjectsInGroup;
        }
        // TODO fill out inboxes with some from: val blahsByRecency = mergeSort[DBObject](isLaterThan)(blahs)
        final List<DBObject> blahsByStrength = mergeSort(new IsStrongerThan(), blahs);

        final Map<Long, List<ObjectId>> inboxNumberToItemIdListMap = new HashMap<Long, List<ObjectId>>();
        for (long inboxNumber = 0; inboxNumber < maxInboxNumberForGroup; inboxNumber++) {
            inboxNumberToItemIdListMap.put(inboxNumber, new ArrayList<ObjectId>());
        }

        final DBCollection userProfilesCol = DBCollections.getInstance().getUserProfilesCol();
        long blahCount = 0;
        for (DBObject blah : blahsByStrength) {
            final long inbox = blahCount % maxInboxNumberForGroup;
            blahCount++;
            final BasicDBObject item = new BasicDBObject(InboxBlahDAOConstants.BLAH_ID, blah.get(BaseDAOConstants.ID).toString());
            item.put(InboxBlahDAOConstants.BLAH_TEXT, blah.get(BlahDAOConstants.TEXT));
            final String blahAuthorId = (String) blah.get(BlahDAOConstants.AUTHOR_ID);
            item.put(InboxBlahDAOConstants.AUTHOR_ID, blahAuthorId);
            item.put(BaseDAOConstants.CREATED, blah.get(BaseDAOConstants.CREATED));
            item.put(InboxBlahDAOConstants.GROUP_ID, groupId);
            item.put(InboxBlahDAOConstants.INBOX_NUMBER, inbox);
            item.put(InboxBlahDAOConstants.TYPE, blah.get(BlahDAOConstants.TYPE_ID));
            Object tmp = blah.get(BlahDAOConstants.BLAH_STRENGTH);
            if (tmp != null) {
                item.put(InboxBlahDAOConstants.BLAH_STRENGTH, tmp);
            }
            tmp = blah.get(BlahDAOConstants.RECENT_BLAH_STRENGTH);
            if (tmp != null) {
                item.put(InboxBlahDAOConstants.RECENT_BLAH_STRENGTH, tmp);
            }
            tmp = blah.get(BlahDAOConstants.PROMOTED_COUNT);
            if (tmp != null) {
                item.put(InboxBlahDAOConstants.UP_VOTES, tmp);
            }
            tmp = blah.get(BlahDAOConstants.DEMOTED_COUNT);
            if (tmp != null) {
                item.put(InboxBlahDAOConstants.DOWN_VOTES, tmp);
            }
            tmp = blah.get(BlahDAOConstants.OPENS);
            if (tmp != null) {
                item.put(InboxBlahDAOConstants.OPENS, tmp);
            }
            tmp = blah.get(BlahDAOConstants.VIEWS);
            if (tmp != null) {
                item.put(InboxBlahDAOConstants.VIEWS, tmp);
            }
            tmp = blah.get(BlahDAOConstants.IMAGE_IDS);
            if (tmp != null) {
                item.put(InboxBlahDAOConstants.IMAGE_IDS, tmp);
            }
            tmp = blah.get(BlahDAOConstants.BADGE_IDS);
            if (tmp != null) {
                item.put(InboxBlahDAOConstants.BADGE_INDICATOR, "b");
            }
            // TODO very expensive fetch should feed off cache (see WRS-252)
            final DBObject userProfileDAO = Utilities.findOneInDB(3, "finding a user profile record", userProfilesCol, new BasicDBObject(BaseDAOConstants.ID, MongoStoreManager.makeObjectId(blahAuthorId)), null);
            if (userProfileDAO != null) {
                final Integer perms = (Integer) userProfileDAO.get(UserProfileDAOConstants.USER_PROFILE_NICKNAME_PERMISSIONS);
                if (perms != null && perms.intValue() == UserProfilePermissions.PUBLIC.getCode()) {
                    tmp = userProfileDAO.get(UserProfileDAOConstants.USER_PROFILE_NICKNAME);
                    if (tmp != null) {
                        item.put(InboxBlahDAOConstants.AUTHOR_NICKNAME, tmp);
                    }
                }
            }

            // insert item into db
            inboxCol.insert(item);
            final ObjectId inboxItemId = (ObjectId) item.get(BaseDAOConstants.ID);
             List<ObjectId> inboxItemIds = inboxNumberToItemIdListMap.get(inbox);
            if (inboxItemIds == null) {
                inboxItemIds = new ArrayList<ObjectId>();
                inboxNumberToItemIdListMap.put(inbox, inboxItemIds);
            }
            inboxItemIds.add(inboxItemId);
        }


        for (Map.Entry<Long, List<ObjectId>> entry : inboxNumberToItemIdListMap.entrySet()) {
            // for each inbox
            final Long key = entry.getKey();
            final Integer inbox = Utilities.getValueAsInteger(key, -1);
            if (inbox == -1) {
                throw new SystemErrorException("Inbox not acceptable");
            }
            final List<ObjectId> dbItemIds = entry.getValue();

            // Holds db ObjectIds
            final List<ObjectId> inboxItemIds = new ArrayList<ObjectId>();
            // Holds cache ids
            final List<String> cacheInboxItemIds = new ArrayList<String>();
            for (ObjectId objectId : dbItemIds) {
                inboxItemIds.add(objectId);
                cacheInboxItemIds.add(BlahCache.makeInboxItemKey(objectId.toString()));
            }

            final String inboxStateDBId = BlahCache.makeInboxStateKey(groupId, inbox);

            // Get existing inbox state from db, if any
            final DBObject oldInboxStateFromDB = Utilities.findOneInDB(3, "finding an inbox state record", inboxStateCol, new BasicDBObject(BaseDAOConstants.ID, inboxStateDBId), null);

            // Write inbox item ids to db (overwrites old)
            final DBObject update = new BasicDBObject(BaseDAOConstants.ID, inboxStateDBId);
            update.put(InboxStateDAOConstants.INBOX_ITEM_IDS, inboxItemIds);
            update.put(InboxStateDAOConstants.INBOX_NUMBER_TOP, maxInboxNumberForGroup);
            if (oldInboxStateFromDB == null) {
                inboxStateCol.insert(update);
            } else {
                inboxStateCol.update(new BasicDBObject(BaseDAOConstants.ID, inboxStateDBId), update); // inserts if doesn't exist
            }

            // Overwrite cache state for inbox with the specified ids
//            cache.setInboxState(groupId, inbox, maxInboxNumberForGroup, cacheInboxItemIds);

            // Garbage-collect obsoleted inbox state ids from cache and from db
            // This is safe since the objects are no longer referenced
            if (oldInboxStateFromDB != null) {
                final List<Object> dbIds = (List<Object>) oldInboxStateFromDB.get(InboxStateDAOConstants.INBOX_ITEM_IDS);
                if (dbIds != null) {
                    final DBObject dbInboxId = new BasicDBObject();
                    for (Object dbId : dbIds) {
//                        cache.getClient().delete(dbId.toString());
                        dbInboxId.put(BaseDAOConstants.ID, dbId);
                        inboxCol.remove(dbInboxId);  // TODO might be better to mark them as deleted
                        deletedStateObjectsInGroup++;
                    }
                }
            }
        }

        Utilities.printit(true, "Deleted " + deletedStateObjectsInGroup + " inbox state objects in group '" + getGroupName(groupId) + "'");

        return deletedStateObjectsInGroup;
    }

    private List<DBObject> getAllBlahs(String groupId) throws DBException, InterruptedException {
        final DBCursor cursor = Utilities.findInDB(3, "finding blahs in a group", blahsCol, new BasicDBObject(BlahDAOConstants.GROUP_ID, groupId), null);

        cursor.addOption(Bytes.QUERYOPTION_SLAVEOK);
        cursor.addOption(Bytes.QUERYOPTION_NOTIMEOUT);

        final List<DBObject> blahs = new ArrayList<DBObject>();
        while (cursor.hasNext()) {
            blahs.add(cursor.next());
        }
        return blahs;
    }


    private List<DBObject> mergeSort(Comparator comparator, List<DBObject> blahs) {
        Collections.sort(blahs, comparator);
        return blahs;
    }

    private class IsLaterThan implements Comparator<DBObject> {
        @Override
        public int compare(DBObject a, DBObject b) {
            final Date obj1 = (Date) a.get(BaseDAOConstants.CREATED);
            final Date obj2 = (Date) b.get(BaseDAOConstants.CREATED);
            return obj1.after(obj2)?1:-1;
        }
    }

    private class IsStrongerThan implements Comparator<DBObject> {
        @Override
        public int compare(DBObject a, DBObject b) {
            final Double obj1 = (Double) a.get(BlahDAOConstants.BLAH_STRENGTH);
            final Double obj2 = (Double) b.get(BlahDAOConstants.BLAH_STRENGTH);
            return (obj1 != null & obj2 != null) ? (obj1 > obj2 ? 1 : -1) : (obj1 == null ? -1 : 1);
        }
    }


    private Date makeDateDaysBeforeToday(int days) {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.set(Calendar.HOUR, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.DATE, days * -1);
        return new Date(cal.getTimeInMillis());
    }
}
