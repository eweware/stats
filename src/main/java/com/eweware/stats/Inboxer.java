package com.eweware.stats;

import com.mongodb.*;
import com.eweware.DBException;
import com.eweware.service.base.CommonUtilities;
import com.eweware.service.base.error.SystemErrorException;
import com.eweware.service.base.store.dao.*;
import com.eweware.service.base.store.dao.schema.type.UserProfilePermissions;
import com.eweware.service.base.store.impl.mongo.dao.MongoStoreManager;
import com.eweware.stats.help.LocalCache;
import com.eweware.stats.help.Utilities;

import java.util.*;
import java.util.regex.Pattern;

/**
 * @author rk@post.harvard.edu
 *         Date: 4/21/13 Time: 3:57 PM
 */
public class Inboxer {

    private static final String USER_HAS_NO_NICKNAME = "0";
    private static final int NUMBER_OF_CACHE_ENTRIES = 10000;
    private static final int TIME_TO_LIVE_IN_SECONDS = 60;
    private static final int TIME_TO_IDLE_IN_SECONDS = 60;
    private static final int MAX_BLAHS_PER_INBOX = 100; // desirable number of blahs per inbox

    private static final int MAX_BLAH_SIZE_IN_BYTES = 4096;

//    private final LocalCache<String, String> _nicknameCache = new LocalCache<String, String>("userNickname", NUMBER_OF_CACHE_ENTRIES, TIME_TO_LIVE_IN_SECONDS, TIME_TO_IDLE_IN_SECONDS);
    private final DBCollection _blahsCol;
    private final DBCollection _groupsCol;

    private final DBCollection _userProfilesCol;
    private AggregationOutput _blahActivityList = null;


//    // absolute maximum number of inboxes per group
//    private int absoluteMaxInboxesPerGroup = 10;
//
//    // number old blahs per inbox
//    private int desiredNumberOldBlahsPerInbox = 5;
//
//    // minimum user strength considered "strong"
//    private double minUserStrength = .80;
//
//
//    // Each inbox contains the following mix of blahs:
//    // from the last D days (recent)
//    private double percentFromLastDdays = .20;
//    // blahs in the 90 percentile blah strength from the last D days (recent)
//    private double fractionOver90strength = .30;
//    // blahs in the 75 percentile blah strength from the last D days (recent)
//    private double fractionOver75strength = .30;
//    // paid blahs
//    private double fractionPaid = .10;
//    // from before last D days but within M months ("old") whose blah strength is in 90 percentile
//    private double fractionOlder = .10;
//

    public Inboxer() throws DBException {
        _blahsCol = DBCollections.getInstance().getBlahsCol();
        _groupsCol = DBCollections.getInstance().getGroupsCol();
        _userProfilesCol = DBCollections.getInstance().getUserProfilesCol();
    }

    public long execute() throws DBException, SystemErrorException, InterruptedException {
        final DBCursor cursor = Utilities.findInDB(3, "finding all group records", _groupsCol, null, null);
        long addedInboxItemCount = 0;
        RefreshActivityList();



        for (DBObject group : cursor) {
            long addedInGroup = buildInbox(group);
            addedInboxItemCount += addedInGroup;
        }
        Utilities.printit(true, "Added total " + addedInboxItemCount + " inbox objects");
        return addedInboxItemCount;
    }

    private String timeString(Date someDate)
    {
        Calendar cal = Calendar.getInstance();
        cal.setTime(someDate);
        String yearPart = Integer.toString(cal.get(cal.YEAR)).substring(2);
        String monthPart = Integer.toString(cal.get(cal.MONTH) + 1);
        if (monthPart.length() == 1)
            monthPart = "0" + monthPart;
        String dayPart = Integer.toString(cal.get(cal.DAY_OF_MONTH));
        if (dayPart.length() == 1)
            dayPart = "0" + dayPart;

        return yearPart + monthPart + dayPart;
    }

    private void RefreshActivityList()  throws DBException
    {
        _blahActivityList = null;

        DBCollection blahCol = DBCollections.getInstance().getTrackBlahCol();
        BasicDBObject upVotesObj = new BasicDBObject("U", new BasicDBObject("$gt", 0));
        BasicDBObject commentsObj = new BasicDBObject("C", new BasicDBObject("$gt", 0));
        BasicDBObject pollVotesObj = new BasicDBObject("P", new BasicDBObject("$gt", 0));
        BasicDBObject downVotesObj = new BasicDBObject("D", new BasicDBObject("$gt", 0));
        BasicDBList typeOrList = new BasicDBList();
        typeOrList.add(upVotesObj);
        typeOrList.add(commentsObj);
        typeOrList.add(pollVotesObj);
        typeOrList.add(downVotesObj);
        BasicDBObject typeOrObj = new BasicDBObject("$or", typeOrList);


        long curTime = System.currentTimeMillis();
        Date endDate = new Date();
        endDate.setTime(curTime);
        long startTime = curTime - 24 * 3600 * 1000; // 24 hours ago
        Date startDate = new Date();
        startDate.setTime(startTime);
        String regexStr = ".*" + timeString(startDate) + "|.*" + timeString(endDate);
        Pattern regexPat = Pattern.compile(regexStr);
        BasicDBObject dateTerm = new BasicDBObject("_id", regexPat);

        BasicDBList typeAndList = new BasicDBList();
        typeAndList.add(dateTerm);
        typeAndList.add(typeOrObj);
        BasicDBObject typeAndObj = new BasicDBObject("$and", typeAndList);

        BasicDBObject matchObj = new BasicDBObject("$match", typeAndObj);


        BasicDBObject groupObj = new BasicDBObject("$group", new BasicDBObject("_id", "$I").append("totalComments", new BasicDBObject("$sum", "$C")).append("totalUpVotes", new BasicDBObject("$sum", "$U")).append("totalDownVotes", new BasicDBObject("$sum", "$D")).append("totalPolls", new BasicDBObject("$sum", "$P")));

        BasicDBList projectList = new BasicDBList();
        projectList.add("$totalComments");
        projectList.add("$totalUpVotes");
        projectList.add("$totalDownVotes");
        projectList.add("$totalPolls");

        BasicDBObject projectObj = new BasicDBObject("$project", new BasicDBObject("total", new BasicDBObject("$add", projectList)));

        _blahActivityList = blahCol.aggregate(matchObj, groupObj, projectObj);
    }

    private String getGroupName(String groupId) throws SystemErrorException, DBException, InterruptedException {
        final DBObject group = Utilities.findOneInDB(3, "finding a group record", _groupsCol, new BasicDBObject(BaseDAOConstants.ID, MongoStoreManager.makeObjectId(groupId)), null);
        return (group == null) ? "Unknown Group" : ((String) group.get(GroupDAOConstants.DISPLAY_NAME));
    }

    private long buildInbox(DBObject group) throws SystemErrorException, DBException, InterruptedException {

        final String groupId = group.get(BaseDAOConstants.ID).toString();
        final Integer lin = (Integer) group.get(GroupDAOConstants.LAST_INBOX_NUMBER);
        Integer lastInboxNumber = (lin == null) ? -1 : lin;

        boolean wraparound = false;
        if (lastInboxNumber > 1000000) { // wrap-around
            lastInboxNumber = -1;
            wraparound = true;
        }

        final List<DBObject> blahs = getRelevantBlahs(groupId, .05, 30);
        final int blahsInGroupCount = blahs.size();

        // If there are no blahs, there's nothing to do
        if (blahsInGroupCount == 0) {
            System.err.println("No blahs in group '" + getGroupName(groupId) + "' id '" + groupId + "'");
            return 0;
        }


        // Sort blahs in memory
        Collections.sort(blahs, new IsStrongerThan());

        // Calculate how many inboxes we need and initialize data
        Integer inboxCount = Utilities.getValueAsInteger(Math.ceil((blahsInGroupCount * 1.0d) / (MAX_BLAHS_PER_INBOX * 1.0d)), 0);
        if (inboxCount > 10)
            inboxCount = 10;

        final List<DBCollection> inboxCollections = new ArrayList<DBCollection>(inboxCount);
        final String inboxDbName = "inboxdb";
        final boolean useCappedCollections = false; // TODO experiment with this option
        for (int number = 0; number < inboxCount; number++) {
            if (wraparound) {
                // TODO we assume that if we are wrapping around inbox numbers, the previous collections will have been dropped by an external (cron) job.
                // If that's not the case, then delete them here.
            }
            final String inboxCollectionName = CommonUtilities.makeInboxCollectionName(groupId, number + lastInboxNumber + 1);
            final boolean collectionExists = DBCollections.getInstance().getDB(inboxDbName).collectionExists(inboxCollectionName);
            if (collectionExists) {
                inboxCollections.add(DBCollections.getInstance().getDB(inboxDbName).getCollection(inboxCollectionName));
            } else {
                if (useCappedCollections) {
                    final DB db = DBCollections.getInstance().getDB(inboxDbName);
                    final BasicDBObject options = new BasicDBObject("capped", true);
                    options.put("max", MAX_BLAHS_PER_INBOX);
                    options.put("size", MAX_BLAHS_PER_INBOX * MAX_BLAH_SIZE_IN_BYTES);
                    inboxCollections.add(db.createCollection(inboxCollectionName, options));
                } else {
                    inboxCollections.add(DBCollections.getInstance().getCollection(inboxDbName, inboxCollectionName));
                }
            }
        }

        // Spread out blahs across inboxes

        final boolean doBulkInserts = true;  // TODO use different bulk sizes to tune performance
//        System.out.println(doBulkInserts ? "Bulk..." : "Singles...");
        List<DBObject> inboxItemsToInsert = new ArrayList<DBObject>();

        // We fill up the inboxes one at a time. Since blahs are sorted by
        // strength, the earlier inboxes have inboxes with the greatest strength.
        // NB: strength is adjusted by recency.
        // Later inboxes will have older or less strong material.
        // The most up-to-date inbox items are in the capped "recent" inbox collection

        if (blahsInGroupCount <= 100) {
            // just copy them all
            for (int blahIndex = 0; blahIndex < blahsInGroupCount; blahIndex++) {
                final BasicDBObject inboxItem = makeInboxItem(groupId, blahs.get(blahIndex));
                inboxCollections.get(0).insert(inboxItem);
            }
        } else {
            // use proper inbox shuffling

            int numHottest = 5;
            int numHot = 20;   // 25
            int numMedium = 40;     // 65
            int numCool = 20;   // 85
            int numBad = 15;    // 100
            int numNew = 0;
            long  minViews = 10;   // to do - should be based on author strength
            int inboxNumber = 0;
            int i = 0;
            List<DBObject> newBlahs = new ArrayList<DBObject>();

            Object  tmp;
            for (Iterator<DBObject> itr = blahs.iterator();itr.hasNext();) {
                DBObject element = itr.next();
                tmp = element.get(InboxBlahDAOConstants.VIEWS);
                if ((tmp == null) || ((Long)tmp < minViews)) {
                    newBlahs.add(element);
                }
            }
            if (newBlahs.size() > 0) {
                int maxNew = 5;
                if (maxNew > newBlahs.size()) {
                    maxNew = newBlahs.size();
                }
                numNew = maxNew;
                numBad -= numNew;
            }

            List<DBObject> curBlahs;

            int    curBlahIndex;
            while (inboxNumber < inboxCount) {
                curBlahs = new ArrayList(blahs);

                if (numNew > 0) {
                    List<DBObject>  newBlahList = new ArrayList<DBObject>(newBlahs);

                    for (i = 0; i < numNew; i++) {
                        curBlahIndex = (int)(Math.random() * newBlahList.size());
                        final BasicDBObject inboxItem = makeInboxItem(groupId, newBlahList.get(curBlahIndex));
                        inboxCollections.get(inboxNumber).insert(inboxItem);
                        curBlahs.remove(newBlahList.get(curBlahIndex));
                        newBlahList.remove(curBlahIndex);  // prevent dupes
                    }
                }


                for (i = 0 ; i < numHottest; i++) {
                    curBlahIndex = GetHottestBlahIndex(curBlahs.size());
                    final BasicDBObject inboxItem = makeInboxItem(groupId, curBlahs.get(curBlahIndex));
                    inboxCollections.get(inboxNumber).insert(inboxItem);
                    curBlahs.remove(curBlahIndex);  // prevent dupes
                }

                for (i = 0 ; i < numHot; i++) {
                    curBlahIndex = GetHotBlahIndex(curBlahs.size());
                    final BasicDBObject inboxItem = makeInboxItem(groupId, curBlahs.get(curBlahIndex));
                    inboxCollections.get(inboxNumber).insert(inboxItem);
                    curBlahs.remove(curBlahIndex);  // prevent dupes
                }

                for (i = 0 ; i < numMedium; i++) {
                    curBlahIndex = GetMediumBlahIndex(curBlahs.size());
                    final BasicDBObject inboxItem = makeInboxItem(groupId, curBlahs.get(curBlahIndex));
                    inboxCollections.get(inboxNumber).insert(inboxItem);
                    curBlahs.remove(curBlahIndex);  // prevent dupes
                }

                for (i = 0 ; i < numCool; i++) {
                    curBlahIndex = GetCoolBlahIndex(curBlahs.size());
                    final BasicDBObject inboxItem = makeInboxItem(groupId, curBlahs.get(curBlahIndex));
                    inboxCollections.get(inboxNumber).insert(inboxItem);
                    curBlahs.remove(curBlahIndex);  // prevent dupes
                }

                for (i = 0 ; i < numBad; i++) {
                    curBlahIndex = GetBadBlahIndex(curBlahs.size());
                    final BasicDBObject inboxItem = makeInboxItem(groupId, curBlahs.get(curBlahIndex));
                    inboxCollections.get(inboxNumber).insert(inboxItem);
                    curBlahs.remove(curBlahIndex);  // prevent dupes
                }

                inboxNumber++;


            }

        }

        // Update group with new inbox range
        final BasicDBObject query = new BasicDBObject(BaseDAOConstants.ID, group.get(BaseDAOConstants.ID));
        final BasicDBObject setter = new BasicDBObject(GroupDAOConstants.FIRST_INBOX_NUMBER, (lastInboxNumber == -1) ? 0 : (lastInboxNumber + 1));
        setter.put(GroupDAOConstants.LAST_INBOX_NUMBER, ((lastInboxNumber + inboxCount)));
        // Update time created and amount of time it took to create it
        final Date lastCreated = (Date) group.get(GroupDAOConstants.LAST_TIME_INBOXES_GENERATED);
        final Date now = new Date();
        setter.put(GroupDAOConstants.LAST_TIME_INBOXES_GENERATED, now);
        if (lastCreated != null) {
            setter.put(GroupDAOConstants.INBOX_GENERATION_DURATION, now.getTime() - lastCreated.getTime());
        }
        _groupsCol.update(query, new BasicDBObject("$set", setter));      // TODO use this in getInbox in rest

        Utilities.printit(true, "Created " + blahsInGroupCount + " inbox items in group '" + getGroupName(groupId) + "'. " + inboxCount + " new inboxes in range: ["
                + setter.get(GroupDAOConstants.FIRST_INBOX_NUMBER) + "," + setter.get(GroupDAOConstants.LAST_INBOX_NUMBER) + "]");

        return blahsInGroupCount;
    }



    private int GetHottestBlahIndex(int listSize) {
        int hottestBlahCount = 10;
        return (int)(Math.random() * hottestBlahCount);
    }

    private int GetHotBlahIndex(int listSize) {
        int min = 0, max = (int)Math.floor(listSize * .1);  // 0-10%
        return min + (int)(Math.random() * (max - min));
    }

    private int GetMediumBlahIndex(int listSize) {
        int min = (int)Math.floor(listSize * .1), max = min + (int)Math.floor(listSize * .1);  // 10-20%
        return min + (int)(Math.random() * (max - min));
    }

    private int GetCoolBlahIndex(int listSize) {
        int min = (int)Math.floor(listSize * .2), max = min + (int)Math.floor(listSize * .5);  // 20-70%
        return min + (int)(Math.random() * (max - min));
    }

    private int GetBadBlahIndex(int listSize) {
        int min = (int)Math.floor(listSize * .7), max = min + (int)Math.floor(listSize * .3);  // 70-100%
        return min + (int)(Math.random() * (max - min));
    }



    private BasicDBObject makeInboxItem(String groupId, DBObject blah) {

        final String blahId = blah.get(BaseDAOConstants.ID).toString();
        final BasicDBObject inboxItem = new BasicDBObject(InboxBlahDAOConstants.BLAH_ID, blahId);

        // IMPORTANT: to fetch the following fields, include them in makeBlahFieldsToReturn() */

        /*
        // this is to set that the blah is recent.  However, we can easily do this server-side
        Date    createDate = (Date)blah.get(BaseDAOConstants.CREATED);
        Date    now = new Date();
        inboxItem.put(BaseDAOConstants.CREATED, createDate);
        long difference = now.getTime() - createDate.getTime();
        long newTime = 1000 * 60 * 60 * 18; // 18 hours
        if (difference < newTime) {
            inboxItem.put(InboxBlahDAOConstants.BLAH_NEWFLAG, true);
        }
        */

        inboxItem.put(InboxBlahDAOConstants.BLAH_TEXT, blah.get(BlahDAOConstants.TEXT));
        inboxItem.put(InboxBlahDAOConstants.TYPE, blah.get(BlahDAOConstants.TYPE_ID));
        inboxItem.put(InboxBlahDAOConstants.GROUP_ID, groupId);
        inboxItem.put(BaseDAOConstants.CREATED, blah.get(BaseDAOConstants.CREATED));
        Object tmp = blah.get(BlahDAOConstants.AUTHOR_ID);
        if (tmp != null) {
            inboxItem.put(InboxBlahDAOConstants.AUTHOR_ID, tmp);
        }

        boolean blahIsActive = checkIfBlahIsActive(blahId);
        if (blahIsActive) {
            inboxItem.put(InboxBlahDAOConstants.RECENTLY_ACTIVE, true);
        }

        tmp = blah.get(BlahDAOConstants.IMAGE_IDS);
        if (tmp != null) {
            inboxItem.put(InboxBlahDAOConstants.IMAGE_IDS, tmp);
        }
        tmp = blah.get(BlahDAOConstants.BADGE_IDS);
        if (tmp != null) {
            inboxItem.put(InboxBlahDAOConstants.BADGE_INDICATOR, "b");
        }

        tmp = blah.get(BlahDAOConstants.BLAH_STRENGTH);
        if (tmp != null) {
            inboxItem.put(InboxBlahDAOConstants.BLAH_STRENGTH, tmp);
        }
        return inboxItem;
    }

    private boolean checkIfBlahIsActive(String blahId)
    {
        boolean isActive = false;

        if (_blahActivityList != null)
        {
            java.lang.Iterable<DBObject>  resultSet = _blahActivityList.results();
            Iterator<DBObject> objList = resultSet.iterator();

            while (objList.hasNext()) {
                DBObject curObj = objList.next();
                if (curObj.get("_id").toString().equals(blahId)) {
                    isActive = true;
                    break;
                }
            }
        }



        return isActive;
    }

    private List<List<DBObject>> makeBulkInsertList(int inboxCount, int bulkInsertMax) {
        final ArrayList<List<DBObject>> list = new ArrayList<List<DBObject>>(inboxCount);
        for (int i = 0; i < inboxCount; i++) {
            list.add(new ArrayList<DBObject>(bulkInsertMax));
        }
        return list;
    }

    private boolean noNickname(String nickname) {
        return (nickname == null || nickname.equals(USER_HAS_NO_NICKNAME));
    }

    // TODO verify that nicknames are evicted from cache after TTL so that we get a refresh
//    private String fetchAndCacheNickname(String blahAuthorId) throws InterruptedException, DBException, SystemErrorException {
//        _nicknameCache.evict(); // TODO ?? do it here ?? is it necessary?
//        final DBObject userProfileDAO = Utilities.findOneInDB(3, "finding a user profile record", _userProfilesCol, new BasicDBObject(BaseDAOConstants.ID, MongoStoreManager.makeObjectId(blahAuthorId)), null);
//        if (userProfileDAO != null) {
//            final Integer perms = (Integer) userProfileDAO.get(UserProfileDAOConstants.USER_PROFILE_NICKNAME_PERMISSIONS);
//            if (perms != null && perms.intValue() == UserProfilePermissions.PUBLIC.getCode()) {
//                String nickname = (String) userProfileDAO.get(UserProfileDAOConstants.USER_PROFILE_NICKNAME);
//                if (nickname != null) {
//                    _nicknameCache.put(blahAuthorId, nickname);
//                    return nickname;
//                }
//            }
//        }
//        _nicknameCache.put(blahAuthorId, USER_HAS_NO_NICKNAME); // mark to prevent excessive DB queries
//        return null;
//    }

    private List<DBObject> getAllBlahs(String groupId) throws DBException, InterruptedException {

        final BasicDBObject fieldsToReturn = makeBlahFieldsToReturn();

        final DBCursor cursor = Utilities.findInDB(3, "finding blahs in a group", _blahsCol, new BasicDBObject(BlahDAOConstants.GROUP_ID, groupId).append("S", new BasicDBObject("$gte", 0)), fieldsToReturn);

        cursor.addOption(Bytes.QUERYOPTION_SLAVEOK);
        cursor.addOption(Bytes.QUERYOPTION_NOTIMEOUT);

        final List<DBObject> blahs = new ArrayList<DBObject>();
        while (cursor.hasNext()) {
            blahs.add(cursor.next());
        }
        return blahs;
    }

    private List<DBObject> getRelevantBlahs(String groupId, double minStrength, long numDays) throws DBException, InterruptedException {

        final BasicDBObject fieldsToReturn = makeBlahFieldsToReturn();
        ArrayList orList = new ArrayList();
        Date    curDate = new Date();
        Date minDate = new Date(curDate.getTime() - numDays * 24 * 3600 * 1000 );
        orList.add(new BasicDBObject("S", new BasicDBObject("$gt", minStrength)));
        orList.add(new BasicDBObject("c", new BasicDBObject("$gt", minDate)));

        BasicDBObject queryObj = new BasicDBObject(BlahDAOConstants.GROUP_ID, groupId).append("S", new BasicDBObject("$gte", 0)).append("$or", orList);

        final DBCursor cursor = Utilities.findInDB(3, "finding blahs in a group", _blahsCol, queryObj, fieldsToReturn);

        cursor.addOption(Bytes.QUERYOPTION_SLAVEOK);
        cursor.addOption(Bytes.QUERYOPTION_NOTIMEOUT);

        final List<DBObject> blahs = new ArrayList<DBObject>();
        while (cursor.hasNext()) {
            blahs.add(cursor.next());
        }

        if (blahs.size() < 100)
            return getAllBlahs(groupId);
        else
            return blahs;
    }

    /** Only these fields will be returned: be sure that this is consistent with buildInbox() */
    private BasicDBObject makeBlahFieldsToReturn() {
        final BasicDBObject fieldsToReturn = new BasicDBObject(BlahDAOConstants.BLAH_STRENGTH, 1);
        fieldsToReturn.put(BlahDAOConstants.TEXT, 1);
        fieldsToReturn.put(BlahDAOConstants.TYPE_ID, 1);
        fieldsToReturn.put(BlahDAOConstants.AUTHOR_ID, 1);
        fieldsToReturn.put(BlahDAOConstants.IMAGE_IDS, 1);
        fieldsToReturn.put(BlahDAOConstants.BADGE_IDS, 1);
        fieldsToReturn.put(BlahDAOConstants.BLAH_STRENGTH, 1);
        fieldsToReturn.put(BaseDAOConstants.CREATED, 1);
        fieldsToReturn.put(BlahDAOConstants.VIEWS, 1);
        return fieldsToReturn;
    }


    private class IsStrongerThan implements Comparator<DBObject> {
        @Override
        public int compare(DBObject a, DBObject b) {
            final Double obj1 = (Double) a.get(BlahDAOConstants.BLAH_STRENGTH);
            final Double obj2 = (Double) b.get(BlahDAOConstants.BLAH_STRENGTH);
            if (obj1 == null)
            {
                return 1;
            }
            else if  (obj2 == null)
            {
                return -1;
            }
            else
                return obj1.compareTo(obj2);
        }
    }
}
