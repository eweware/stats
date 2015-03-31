package com.eweware.stats;

import com.eweware.service.base.store.dao.schema.type.BooleanDataTypeValidator;
import com.mongodb.*;
import com.eweware.DBException;
import com.eweware.service.base.CommonUtilities;
import com.eweware.service.base.error.SystemErrorException;
import com.eweware.service.base.store.dao.*;
import com.eweware.service.base.store.dao.schema.type.UserProfilePermissions;
import com.eweware.service.base.store.impl.mongo.dao.MongoStoreManager;
import com.eweware.stats.help.LocalCache;
import com.eweware.stats.help.Utilities;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URL;
import java.util.*;
import java.util.regex.Pattern;

import com.google.appengine.tools.cloudstorage.*;
import com.sun.org.apache.xpath.internal.operations.Bool;

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
            Boolean inactive = (Boolean)group.get("IN");

            if (inactive == null || inactive != true) {
                long addedInGroup = buildSafeInbox(group);
                addedInGroup += buildMatureInbox(group);
                addedInboxItemCount += addedInGroup;
            }

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


    // build an inbox of nothing but safe blahs
    private long buildSafeInbox(DBObject group) throws SystemErrorException, DBException, InterruptedException {

        final String groupId = group.get(BaseDAOConstants.ID).toString();
        final Integer lin = (Integer) group.get(GroupDAOConstants.LAST_SAFE_INBOX_NUMBER);
        Integer lastInboxNumber = (lin == null) ? -1 : lin;

        if (lastInboxNumber > 1000000) { // wrap-around
            lastInboxNumber = -1;
        }

        Integer channelHalfLife = (Integer)group.get("halflife");
        if (channelHalfLife == null)
            channelHalfLife = 15;

        final List<DBObject> blahs = getRelevantSafeBlahs(groupId, .05, channelHalfLife * 2, -1);
        final int blahsInGroupCount = blahs.size();

        // If there are no blahs, there's nothing to do
        if (blahsInGroupCount == 0) {
            System.err.println("No safe blahs in group '" + getGroupName(groupId) + "' id '" + groupId + "'");
            return 0;
        }


        // Sort blahs in memory
        Collections.sort(blahs, new IsStrongerThan());

        return intBuildInbox(group, blahs, lastInboxNumber, true);
    }

    // build an inbox that can contain mature blahs also
    private long buildMatureInbox(DBObject group) throws SystemErrorException, DBException, InterruptedException {
        final String groupId = group.get(BaseDAOConstants.ID).toString();
        final Integer lin = (Integer) group.get(GroupDAOConstants.LAST_INBOX_NUMBER);
        Integer lastInboxNumber = (lin == null) ? -1 : lin;

        if (lastInboxNumber > 1000000) { // wrap-around
            lastInboxNumber = -1;
        }

        Integer channelHalfLife = (Integer)group.get("halflife");
        if (channelHalfLife == null)
            channelHalfLife = 15;

        final List<DBObject> blahs = getRelevantBlahs(groupId, .05, channelHalfLife * 2, -1);
        final int blahsInGroupCount = blahs.size();

        // If there are no blahs, there's nothing to do
        if (blahsInGroupCount == 0) {
            System.err.println("No blahs in group '" + getGroupName(groupId) + "' id '" + groupId + "'");
            return 0;
        }


        // Sort blahs in memory
        Collections.sort(blahs, new IsStrongerThan());

        return intBuildInbox(group, blahs, lastInboxNumber, false);
    }


    private long intBuildInbox(DBObject group, List<DBObject> blahs, Integer lastInboxNumber, Boolean safe) throws SystemErrorException, DBException, InterruptedException {

        final String groupId = group.get(BaseDAOConstants.ID).toString();

        final int blahsInGroupCount = blahs.size();
        // Calculate how many inboxes we need and initialize data
        Integer inboxCount = Utilities.getValueAsInteger(Math.ceil((blahsInGroupCount * 1.0d) / (MAX_BLAHS_PER_INBOX * 1.0d)), 0);
        if (inboxCount > 5)
            inboxCount = 5;

        final List<DBCollection> inboxCollections = new ArrayList<DBCollection>(inboxCount);
        final String inboxDbName = "inboxdb";

        for (int number = 0; number < inboxCount; number++) {

            final String inboxCollectionName = CommonUtilities.makeInboxCollectionName(groupId, number + lastInboxNumber + 1, safe);
            final boolean collectionExists = DBCollections.getInstance().getDB(inboxDbName).collectionExists(inboxCollectionName);
            if (collectionExists) {
                inboxCollections.add(DBCollections.getInstance().getDB(inboxDbName).getCollection(inboxCollectionName));
            } else {
                inboxCollections.add(DBCollections.getInstance().getCollection(inboxDbName, inboxCollectionName));

            }
        }

        if (blahsInGroupCount <= 100) {
            // just copy them all
            for (int blahIndex = 0; blahIndex < blahsInGroupCount; blahIndex++) {
                final BasicDBObject inboxItem = makeInboxItem(groupId, blahs.get(blahIndex));
                inboxCollections.get(0).insert(inboxItem);
            }
        } else {
            // do the inbox shuffling
            Integer channelHalfLife = (Integer)group.get("halflife");
            if (channelHalfLife == null)
                channelHalfLife = 15;

            Integer recentDays = (Integer)group.get("recentdays");
            if (recentDays == null)
                recentDays = 2;

            Integer newlyborn = (Integer)group.get("newlyborn");
            if (newlyborn == null)
                newlyborn = 12;  // # hours for newly born

            // TO DO:  really this should based on # channel recent members who have seen it.
            Integer channelNewViews = (Integer)group.get("newviews");
            if (channelNewViews == null) {
                Long numUsers = (Long) group.get("U");
                if ((numUsers == null) || (numUsers < 100))
                    channelNewViews = 10;
                else {
                    channelNewViews = (int)(numUsers / 5);
                    if (channelNewViews > 50)
                        channelNewViews = 50;
                }
            }

            Integer maxActive = (Integer)group.get("maxActive");
            if (maxActive == null)
                maxActive = 30;

            Integer maxNewBorn = (Integer)group.get("maxnewborn");
            if (maxNewBorn == null)
                maxNewBorn = 20;

            Integer maxRecent = (Integer)group.get("maxrecent");
            if (maxRecent == null)
                maxRecent = 20;

            Integer maxUnseen = (Integer)group.get("maxunseen");
            if (maxUnseen == null)
                maxUnseen = 20;

            // computed collections
            // handle new blahs
            List<DBObject> unseenBlahs = new ArrayList<DBObject>();
            List<DBObject> recentBlahs = new ArrayList<DBObject>();
            List<DBObject> newlyBornBlahs = new ArrayList<DBObject>();
            List<DBObject> activeBlahs = new ArrayList<DBObject>();
            Calendar recentCalender = Calendar.getInstance();
            recentCalender.add(Calendar.DAY_OF_MONTH, -recentDays);
            Date recentDate = recentCalender.getTime();

            Calendar newlybornCalendar = Calendar.getInstance();
            newlybornCalendar.add(Calendar.HOUR_OF_DAY, -newlyborn);
            Date newlyBornDate = newlybornCalendar.getTime();

            Date curDate;
            Long viewCount;
            String blahId;

            for (Iterator<DBObject> itr = blahs.iterator();itr.hasNext();) {
                DBObject element = itr.next();

                blahId = element.get("_id").toString();
                viewCount = (Long)element.get(BlahDAOConstants.VIEWS);
                if (checkIfBlahIsActive(blahId)) {
                    activeBlahs.add(element);
                }

                if ((viewCount == null) || (viewCount < channelNewViews)) {
                    unseenBlahs.add(element);
                }

                curDate = (Date)element.get(BaseDAOConstants.CREATED);
                if (curDate.compareTo(newlyBornDate) > 0) {
                    newlyBornBlahs.add(element);
                }

                if (curDate.compareTo(recentDate) > 0) {
                    recentBlahs.add(element);
                }
            }



            int inboxNumber = 0;
            int i = 0;

            List<DBObject> curBlahs;
            List<DBObject> tempActive;
            List<DBObject> tempUnseen;
            List<DBObject> tempNewborn;
            List<DBObject> tempRecent;


            int    curBlahIndex;
            DBObject curBlah;

            while (inboxNumber < inboxCount) {
                curBlahs = new ArrayList(blahs);
                tempActive = new ArrayList(activeBlahs);
                tempUnseen = new ArrayList(unseenBlahs);
                tempNewborn = new ArrayList(newlyBornBlahs);
                tempRecent = new ArrayList(recentBlahs);

                int numActive = tempActive.size();
                if (numActive > maxActive)
                    numActive = maxActive;

                if (numActive > 0) {


                    for (i = 0; i < numActive; i++) {
                        curBlahIndex = (int)(Math.random() * tempActive.size());
                        curBlah = tempActive.get(curBlahIndex);
                        final BasicDBObject inboxItem = makeInboxItem(groupId, curBlah);
                        inboxCollections.get(inboxNumber).insert(inboxItem);
                        tempActive.remove(curBlahIndex);
                        tempUnseen.remove(curBlah);
                        tempNewborn.remove(curBlah);
                        tempRecent.remove(curBlah);
                        curBlahs.remove(curBlah); // prevent dupes
                    }
                }

                int numUnseen = tempUnseen.size();
                if (numUnseen > maxUnseen)
                    numUnseen = maxUnseen;

                if (numUnseen > 0) {
                    for (i = 0; i < numUnseen; i++) {
                        curBlahIndex = (int)(Math.random() * tempUnseen.size());
                        curBlah = tempUnseen.get(curBlahIndex);
                        final BasicDBObject inboxItem = makeInboxItem(groupId, curBlah);
                        inboxCollections.get(inboxNumber).insert(inboxItem);
                        tempUnseen.remove(curBlahIndex);
                        tempNewborn.remove(curBlah);
                        tempRecent.remove(curBlah);
                        curBlahs.remove(curBlah); // prevent dupes
                    }
                }

                int numNewlyBorn = tempNewborn.size();
                if (numNewlyBorn > maxNewBorn)
                    numNewlyBorn = maxNewBorn;

                if (numNewlyBorn > 0) {
                    for (i = 0; i < numNewlyBorn; i++) {
                        curBlahIndex = (int)(Math.random() * tempNewborn.size());
                        curBlah = tempNewborn.get(curBlahIndex);
                        final BasicDBObject inboxItem = makeInboxItem(groupId, curBlah);
                        inboxCollections.get(inboxNumber).insert(inboxItem);
                        tempNewborn.remove(curBlahIndex);
                        tempRecent.remove(curBlah);
                        curBlahs.remove(curBlah); // prevent dupes
                    }
                }

                int numRecent = tempRecent.size();
                if (numRecent > maxRecent)
                    numRecent = maxRecent;

                if (numRecent > 0) {
                    for (i = 0; i < numRecent; i++) {
                        curBlahIndex = (int)(Math.random() * tempRecent.size());
                        curBlah = tempRecent.get(curBlahIndex);
                        final BasicDBObject inboxItem = makeInboxItem(groupId, curBlah);
                        inboxCollections.get(inboxNumber).insert(inboxItem);
                        tempRecent.remove(curBlahIndex);
                        curBlahs.remove(curBlah); // prevent dupes
                    }
                }


                int numHottest = 5;
                int numBad = 5;

                int totalClaimed = numActive + numUnseen + numNewlyBorn + numRecent + numHottest + numBad;
                int totalUnclaimed = MAX_BLAHS_PER_INBOX - totalClaimed;

                int numHot = (int)(totalUnclaimed * 0.3);
                int numMedium = (int)(totalUnclaimed * 0.5);
                int numCool = totalUnclaimed - (numHot + numMedium);


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
        BasicDBObject setter;
        if (safe) {
            setter = new BasicDBObject(GroupDAOConstants.FIRST_SAFE_INBOX_NUMBER, (lastInboxNumber == -1) ? 0 : (lastInboxNumber + 1));
            setter.put(GroupDAOConstants.LAST_SAFE_INBOX_NUMBER, ((lastInboxNumber + inboxCount)));
        } else {
            setter = new BasicDBObject(GroupDAOConstants.FIRST_INBOX_NUMBER, (lastInboxNumber == -1) ? 0 : (lastInboxNumber + 1));
            setter.put(GroupDAOConstants.LAST_INBOX_NUMBER, ((lastInboxNumber + inboxCount)));
        }
        // Update time created and amount of time it took to create it
        final Date lastCreated = (Date) group.get(GroupDAOConstants.LAST_TIME_INBOXES_GENERATED);
        final Date now = new Date();
        setter.put(GroupDAOConstants.LAST_TIME_INBOXES_GENERATED, now);
        if (lastCreated != null) {
            setter.put(GroupDAOConstants.INBOX_GENERATION_DURATION, now.getTime() - lastCreated.getTime());
        }
        _groupsCol.update(query, new BasicDBObject("$set", setter));      // TODO use this in getInbox in rest

        if (safe)
            Utilities.printit(true, "Created " + blahsInGroupCount + " inbox items in group '" + getGroupName(groupId) + "'. " + inboxCount + " new safe inboxes in range: ["
                    + setter.get(GroupDAOConstants.FIRST_SAFE_INBOX_NUMBER) + "," + setter.get(GroupDAOConstants.LAST_SAFE_INBOX_NUMBER) + "]");
        else
            Utilities.printit(true, "Created " + blahsInGroupCount + " inbox items in group '" + getGroupName(groupId) + "'. " + inboxCount + " new inboxes in range: ["
                    + setter.get(GroupDAOConstants.FIRST_INBOX_NUMBER) + "," + setter.get(GroupDAOConstants.LAST_INBOX_NUMBER) + "]");

        return blahsInGroupCount;
    }



    private int GetHottestBlahIndex(int listSize) {
        int hottestBlahCount = listSize / 100;  // 1% hottest
        if (hottestBlahCount < 30)
            hottestBlahCount = 30;  // at least 30 sample size..
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

    private String truncate(String str, int limit) {

        if (str.length()< limit)
            return str;

        String[] bits = str.split(" ");
        int i = 1;
        String finalStr = bits[0];

        while (i < bits.length) {
            if (finalStr.length() + bits[i].length() + 1 < limit)
                finalStr = finalStr + " " + bits[i++];
            else {
                finalStr += "…";
                break;
            }
        }

        return finalStr;
    }

    private BasicDBObject makeInboxItem(String groupId, DBObject blah) {

        final String blahId = blah.get(BaseDAOConstants.ID).toString();
        final BasicDBObject inboxItem = new BasicDBObject(InboxBlahDAOConstants.BLAH_ID, blahId);


        String blahRawText = (String)blah.get(BlahDAOConstants.TEXT);
        if ((blahRawText != null) && (blahRawText.length() > 64)) {
            // truncate it
            blahRawText = truncate(blahRawText, 64);
        }

        inboxItem.put(InboxBlahDAOConstants.BLAH_TEXT, blahRawText);
        inboxItem.put(InboxBlahDAOConstants.FLAGGEDCONTENT, blah.get(BlahDAOConstants.FLAGGEDCONTENT));
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



    private List<DBObject> getAllBlahs(String groupId) throws DBException, InterruptedException {

        final BasicDBObject fieldsToReturn = makeBlahFieldsToReturn();
        Date    curDate = new Date();
        Calendar cal = new GregorianCalendar();
        cal.setTime(curDate);
        cal.add(Calendar.DAY_OF_MONTH, -60);
        Date minDate = cal.getTime();
        ArrayList andList = new ArrayList();
        andList.add(new BasicDBObject(BlahDAOConstants.GROUP_ID, groupId));
        andList.add(new BasicDBObject("S", new BasicDBObject("$gte", 0)));

        if (countAllBlahs(groupId) > 100)
            andList.add(new BasicDBObject("c", new BasicDBObject("$gt", minDate)));

        BasicDBObject queryObj = new BasicDBObject("$and", andList);

        DBCursor cursor = Utilities.findInDB(3, "finding blahs in a group", _blahsCol, queryObj, fieldsToReturn);


        cursor.addOption(Bytes.QUERYOPTION_SLAVEOK);
        cursor.addOption(Bytes.QUERYOPTION_NOTIMEOUT);

        final List<DBObject> blahs = new ArrayList<DBObject>();
        while (cursor.hasNext()) {
            blahs.add(cursor.next());
        }
        return blahs;
    }

    private long countAllBlahs(String groupId) throws DBException, InterruptedException {

        final long theCount = Utilities.getCountFromDB(3, "finding blahs in a group", _blahsCol,  new BasicDBObject(BlahDAOConstants.GROUP_ID, groupId).append("S", new BasicDBObject("$gte", 0)));

        return theCount;
    }

    private List<DBObject> getAllSafeBlahs(String groupId) throws DBException, InterruptedException {

        final BasicDBObject fieldsToReturn = makeBlahFieldsToReturn();

        Date    curDate = new Date();
        Calendar cal = new GregorianCalendar();
        cal.setTime(curDate);
        cal.add(Calendar.DAY_OF_MONTH, -60);
        Date minDate = cal.getTime();
        ArrayList safeList = new ArrayList();
        safeList.add(new BasicDBObject(BlahDAOConstants.FLAGGEDCONTENT, new BasicDBObject("$exists", false)));
        safeList.add(new BasicDBObject(BlahDAOConstants.FLAGGEDCONTENT, false));

        ArrayList andList = new ArrayList();
        andList.add(new BasicDBObject(BlahDAOConstants.GROUP_ID, groupId));
        andList.add(new BasicDBObject("S", new BasicDBObject("$gt", 0)));
        if (countAllSafeBlahs(groupId) > 100)
            andList.add(new BasicDBObject("c", new BasicDBObject("$gt", minDate)));

        andList.add(new BasicDBObject("$or", safeList));



        BasicDBObject queryObj = new BasicDBObject("$and", andList);

        final DBCursor cursor = Utilities.findInDB(3, "finding safe blahs in a group", _blahsCol, queryObj, fieldsToReturn);

        cursor.addOption(Bytes.QUERYOPTION_SLAVEOK);
        cursor.addOption(Bytes.QUERYOPTION_NOTIMEOUT);

        final List<DBObject> blahs = new ArrayList<DBObject>();
        while (cursor.hasNext()) {
            blahs.add(cursor.next());
        }
        return blahs;
    }

    private long countAllSafeBlahs(String groupId) throws DBException, InterruptedException {

        ArrayList safeList = new ArrayList();
        safeList.add(new BasicDBObject(BlahDAOConstants.FLAGGEDCONTENT, new BasicDBObject("$exists", false)));
        safeList.add(new BasicDBObject(BlahDAOConstants.FLAGGEDCONTENT, false));

        final long theCount = Utilities.getCountFromDB(3, "finding blahs in a group", _blahsCol, new BasicDBObject(BlahDAOConstants.GROUP_ID, groupId).append("S", new BasicDBObject("$gte", 0)).append("$or", safeList));

        return theCount;
    }


    private List<DBObject> getRelevantBlahs(String groupId, double minStrength, int numDays, long maxSize) throws DBException, InterruptedException {

        final BasicDBObject fieldsToReturn = makeBlahFieldsToReturn();
        ArrayList orList = new ArrayList();
        Date    curDate = new Date();
        Calendar cal = new GregorianCalendar();
        cal.setTime(curDate);
        cal.add(Calendar.DAY_OF_MONTH, -numDays);
        Date minDate = cal.getTime();
        ArrayList andList = new ArrayList();
        andList.add(new BasicDBObject(BlahDAOConstants.GROUP_ID, groupId));
        andList.add(new BasicDBObject("S", new BasicDBObject("$gt", minStrength)));
        andList.add(new BasicDBObject("c", new BasicDBObject("$gt", minDate)));

        BasicDBObject queryObj = new BasicDBObject("$and", andList);


        final DBCursor cursor = Utilities.findInDB(3, "finding blahs in a group", _blahsCol, queryObj, fieldsToReturn);

        cursor.addOption(Bytes.QUERYOPTION_SLAVEOK);
        cursor.addOption(Bytes.QUERYOPTION_NOTIMEOUT);

        final List<DBObject> blahs = new ArrayList<DBObject>();
        while (cursor.hasNext()) {
            blahs.add(cursor.next());
        }

        if (blahs.size() < 100) {
            System.out.println("Not enough blahs - fetching all of them");
             return blahs;//getAllBlahs(groupId);
        }
        else
            return blahs;
    }

    private List<DBObject> getRelevantSafeBlahs(String groupId, double minStrength, int numDays, long maxSize) throws DBException, InterruptedException {

        final BasicDBObject fieldsToReturn = makeBlahFieldsToReturn();
        Date    curDate = new Date();

        Calendar cal = new GregorianCalendar();
        cal.setTime(curDate);
        cal.add(Calendar.DAY_OF_MONTH, -numDays);
        Date minDate = cal.getTime();

        ArrayList safeList = new ArrayList();
        safeList.add(new BasicDBObject(BlahDAOConstants.FLAGGEDCONTENT, new BasicDBObject("$exists", false)));
        safeList.add(new BasicDBObject(BlahDAOConstants.FLAGGEDCONTENT, false));

        ArrayList andList = new ArrayList();
        andList.add(new BasicDBObject(BlahDAOConstants.GROUP_ID, groupId));
        andList.add(new BasicDBObject("S", new BasicDBObject("$gt", minStrength)));
        andList.add(new BasicDBObject("c", new BasicDBObject("$gt", minDate)));
        andList.add(new BasicDBObject("$or", safeList));



        BasicDBObject queryObj = new BasicDBObject("$and", andList);

        final DBCursor cursor = Utilities.findInDB(3, "finding safe blahs in a group", _blahsCol, queryObj, fieldsToReturn);

        cursor.addOption(Bytes.QUERYOPTION_SLAVEOK);
        cursor.addOption(Bytes.QUERYOPTION_NOTIMEOUT);

        final List<DBObject> blahs = new ArrayList<DBObject>();
        while (cursor.hasNext()) {
            blahs.add(cursor.next());
        }

        if (blahs.size() < 100) {
            System.out.println("Not enough blahs - fetching all of them");
            return blahs;// getAllSafeBlahs(groupId);
        }
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
        fieldsToReturn.put(BlahDAOConstants.FLAGGEDCONTENT, 1);
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
                return obj2.compareTo(obj1);
        }
    }
}
