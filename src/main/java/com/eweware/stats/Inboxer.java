package com.eweware.stats;

import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.OperationContext;
import com.microsoft.azure.storage.queue.CloudQueue;
import com.microsoft.azure.storage.queue.CloudQueueClient;
import com.microsoft.azure.storage.queue.CloudQueueMessage;
import com.mongodb.*;
import com.eweware.DBException;
import com.eweware.service.base.CommonUtilities;
import com.eweware.service.base.error.SystemErrorException;
import com.eweware.service.base.store.dao.*;
import com.eweware.service.base.store.impl.mongo.dao.MongoStoreManager;
import com.eweware.stats.help.Utilities;
import com.mongodb.util.JSON;
import org.bson.types.ObjectId;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;

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
    private final DBCollection _userGroupsCol;

    private final DBCollection _generationInfoCol;
    private final DBCollection _userGroupInfoCol;
    private final DBCollection _blahInfoCol;

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


    public static final String DEV_ACCOUNT_NAME = "weihanstorage";
    public static final String DEV_ACCOUNT_KEY = "PKz1eXkKlu07u4SpfyxfCvO1BH4yZCnuXhrQbebIaOdmUfGGD6qV8r+lycj7sNXSwtVTHpo/nJBlHVa4oavNgg==";

    public static final String QA_ACCOUNT_NAME = "heardqueueqa";
    public static final String QA_ACCOUNT_KEY = "dr3XhxQEKlwqSGPe9+YJiwCUZ2v7izLOR31xED66joJcyUWJoDU9A1Hl0HzlXa/WsLorEYEpscNU06p0TYGcjA==";

    public static final String PROD_ACCOUNT_NAME = "";
    public static final String PROD_ACCOUNT_KEY = "";


    public static String STORAGE_CONNECTION_STRING;

    public static final String INBOX_TASK_QUEUE = "inboxtasks";

    private CloudQueueClient queueClient;
    private CloudQueue inboxTaskQueue;

    private int QUEUE_VISIBLE_TIMEOUT_SECONDS;
    private long NO_TASK_WAIT_MILLIS;

    private double RELEVANT_BLAH_MIN_STRENGTH;
    private int RECENT_BLAH_DAYS;

    public static final int GENERATE_INBOX = 0;
    public static final int GENERATE_INBOX_NEW_CLUSTER = 1;

    private String environment;

    public Inboxer(String env) throws DBException {
        _blahsCol = DBCollections.getInstance().getBlahsCol();
        _groupsCol = DBCollections.getInstance().getGroupsCol();
        _userProfilesCol = DBCollections.getInstance().getUserProfilesCol();
        _userGroupsCol = DBCollections.getInstance().getUserGroupsCol();

        _generationInfoCol = DBCollections.getInstance().getGenerationInfoCol();
        _userGroupInfoCol = DBCollections.getInstance().getUserGroupInfoCol();
        _blahInfoCol = DBCollections.getInstance().getBlahInfoCol();

        environment = env;
    }

    public void execute() throws DBException, SystemErrorException, InterruptedException {

        try {
            getProperties();

            if (environment.equals("dev")) {
                STORAGE_CONNECTION_STRING = "DefaultEndpointsProtocol=http;AccountName=" + DEV_ACCOUNT_NAME + ";AccountKey=" + DEV_ACCOUNT_KEY;
            }
            else if (environment.equals("qa")) {
                STORAGE_CONNECTION_STRING = "DefaultEndpointsProtocol=http;AccountName=" + QA_ACCOUNT_NAME + ";AccountKey=" + QA_ACCOUNT_KEY;
            }
            else if (environment.equals("prod")) {
                STORAGE_CONNECTION_STRING = "DefaultEndpointsProtocol=http;AccountName=" + PROD_ACCOUNT_NAME + ";AccountKey=" + PROD_ACCOUNT_KEY;
            }
            else {
                STORAGE_CONNECTION_STRING = "DefaultEndpointsProtocol=http;AccountName=" + DEV_ACCOUNT_NAME + ";AccountKey=" + DEV_ACCOUNT_KEY;
            }

            initializeQueue();

            // continuously get task to work on
            while (true) {
                CloudQueueMessage message = inboxTaskQueue.retrieveMessage(QUEUE_VISIBLE_TIMEOUT_SECONDS, null, new OperationContext());
                if (message != null) {
                    // Process the message within certain time, and then delete the message.
                    BasicDBObject task = (BasicDBObject) JSON.parse(message.getMessageContentAsString());
                    try {
                        processTask(task);
                        inboxTaskQueue.deleteMessage(message);
                    }
                    catch (TaskException e) {
                        // there is something wrong about the task
                        if (e.type == TaskExceptionType.SKIP) {
                            System.out.println(e.getMessage() + ", task skipped");
                            inboxTaskQueue.deleteMessage(message);
                        }
                        else if (e.type == TaskExceptionType.RECOMPUTE) {
                            System.out.println(e.getMessage() + ", task put back to queue");
                            inboxTaskQueue.updateMessage(message, 0);
                        }
                        else
                            e.printStackTrace();
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                else {
                    System.out.println("[InboxWorker] no tasks, sleep for " + NO_TASK_WAIT_MILLIS + " milliseconds.");
                    Thread.sleep(NO_TASK_WAIT_MILLIS);
                }
            }
        }
        catch (Exception e) {
            System.out.println("Error in initializing Azure queue");
            e.printStackTrace();
        }
    }

    private void initializeQueue() throws Exception {
        System.out.print("Initializing Azure Storage Queue service... ");

        // Retrieve storage account from connection-string.
        CloudStorageAccount storageAccount =
                CloudStorageAccount.parse(STORAGE_CONNECTION_STRING);

        // Create the queue client.
        queueClient = storageAccount.createCloudQueueClient();

        // Retrieve a reference to a queue.
        inboxTaskQueue = queueClient.getQueueReference(INBOX_TASK_QUEUE);

        // Create the queue if it doesn't already exist.
        inboxTaskQueue.createIfNotExists();

        System.out.println("done");
    }

    private void getProperties() throws IOException {
        System.out.println("Load service properties");
        Properties prop = new Properties();
        String propFileName;

        if (environment.equals("dev")) propFileName = "./dev.properties";
        else if (environment.equals("qa")) propFileName = "./qa.properties";
        else propFileName = "./prod.properties";

        FileInputStream is = new FileInputStream(propFileName);

        if (is == null) {
            throw new FileNotFoundException("property file '" + propFileName + "' not found");
        }
        prop.load(is);

        // InboxWorker
        QUEUE_VISIBLE_TIMEOUT_SECONDS = Integer.parseInt(prop.getProperty("iw.queue_visible_timeout_seconds", "30"));
        NO_TASK_WAIT_MILLIS = Long.parseLong(prop.getProperty("iw.no_task_wait_millis", "100000"));
        RELEVANT_BLAH_MIN_STRENGTH = Double.parseDouble(prop.getProperty("iw.relevant_blah_min_strength", "0.05"));
        RECENT_BLAH_DAYS = Integer.parseInt(prop.getProperty("iw.recent_blah_days", "30"));

    }

    private boolean processTask(BasicDBObject task) throws TaskException, DBException, InterruptedException, SystemErrorException {
        System.out.println();

        //final DBCursor cursor = Utilities.findInDB(3, "finding all group records", _groupsCol, null, null);
        long addedInboxItemCount = 0;

        RefreshActivityList();

        final String groupId = task.getString("G");
        BasicDBObject group = (BasicDBObject) _groupsCol.findOne(new BasicDBObject("_id", new ObjectId(groupId)));
        if (group == null) throw new TaskException("Error : groupd not exist id : " + groupId, TaskExceptionType.SKIP);

        final Integer taskType = (Integer) task.get("T");
        String genId = task.getString("GEN");

        // get the cohort info sub-document of the latest generation
        BasicDBObject generation = (BasicDBObject)_generationInfoCol.findOne(new BasicDBObject("_id", new ObjectId(genId)));
        BasicDBObject cohortInfoDoc = (BasicDBObject) generation.get("CHI");

        // build inboxes
        long addedInGroup = buildInbox(group, cohortInfoDoc, genId, false); // unsafe inbox
//            addedInGroup += buildInbox(group, cohortInfoDoc, genId, true); // safe inbox
        addedInboxItemCount += addedInGroup;

        // if this is a new generation of cohorts for this group
        if (taskType == GENERATE_INBOX_NEW_CLUSTER) {
            updateNewClusterInfo(groupId, genId);
        }

        Utilities.printit(true, "Added total " + addedInboxItemCount + " inbox objects");
        System.out.println();
        return true;
    }

    private enum TaskExceptionType {
        RECOMPUTE, SKIP
    }

    private class TaskException extends Exception {
        private TaskExceptionType type;
        private TaskException(String msg, TaskExceptionType type) {
            super(msg);
            this.type = type;
        }
    }

    private List<String> getUserList(String groupId) {
        BasicDBObject query = new BasicDBObject("G", new ObjectId(groupId));
        DBCursor cursor = _userGroupInfoCol.find(query);
        List<String> userIdList = new ArrayList<String>();
        while (cursor.hasNext()) {
            BasicDBObject userGroupInfo = (BasicDBObject) cursor.next();
            userIdList.add(userGroupInfo.getObjectId("U").toString());
        }
        cursor.close();
        return userIdList;
    }

    private void updateNewClusterInfo(String groupId, String nextGenId) throws InterruptedException, DBException, SystemErrorException {

//        // update users' cohort info
//        // get user list for this group
//        List<String> userIdList = getUserList(groupId);
//        // for each user, get next cohort and replace current cohort
//        for (String userId : userIdList) {
//            BasicDBObject query = new BasicDBObject("U", new ObjectId(userId));
//            query.append("G", new ObjectId(groupId));
//
//            BasicDBObject userGroupInfo = (BasicDBObject) _userGroupInfoCol.findOne(query);
//            List<ObjectId> cohortList = (List<ObjectId>) userGroupInfo.get("CHN");
//
//            BasicDBObject values = new BasicDBObject("CH", cohortList);
//            values.append("CHN", null);
//            BasicDBObject setter = new BasicDBObject("$set", values);
//            _userGroupInfoCol.update(query, setter);
//        }

        Utilities.printit(true, "\tNew cohort generation <" + nextGenId + "> for group '" + getGroupName(groupId) + "' info is updated.");

        // update group's current generation ID
        BasicDBObject values = new BasicDBObject("CG", new ObjectId(nextGenId));
        BasicDBObject setter = new BasicDBObject("$set", values);
        _groupsCol.update(new BasicDBObject("_id", new ObjectId(groupId)), setter);
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
        // TODO check blahstats to see whether the blah is recently active
        // for now, mark all as inactive
        _blahActivityList = null;
        return;
/*
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
        */
    }

    private String getGroupName(String groupId) throws SystemErrorException, DBException, InterruptedException {
        final DBObject group = Utilities.findOneInDB(3, "finding a group record", _groupsCol, new BasicDBObject(BaseDAOConstants.ID, MongoStoreManager.makeObjectId(groupId)), null);
        return (group == null) ? "Unknown Group" : ((String) group.get(GroupDAOConstants.DISPLAY_NAME));
    }


    // build an inbox of nothing but safe blahs
    private long buildInbox(DBObject group, DBObject cohortInfoDoc, String currentGenId, boolean safe) throws SystemErrorException, DBException, InterruptedException {
        final String groupId = group.get(BaseDAOConstants.ID).toString();

        long addedInGroup = 0;
        // TEST
        //if (!groupId.equals("522ccb78e4b0a35dadfcf73f")) return 0;

        // for each cohort, sort by cohort-strength, and build inbox
        for (String cohortId : cohortInfoDoc.keySet()) {
            BasicDBObject cohortInboxInfo = (BasicDBObject) cohortInfoDoc.get(cohortId);
            String inboxKeyString = safe ? "LS" : "L";
            long lastInboxNumber = cohortInboxInfo.getInt(inboxKeyString, -1);

            if (lastInboxNumber > 1000000) { // wrap-around
                lastInboxNumber = -1;
            }

            final List<DBObject> blahs = getRelevantBlahs(groupId, cohortId, RELEVANT_BLAH_MIN_STRENGTH, RECENT_BLAH_DAYS, safe);
            final int blahsInGroupCount = blahs.size();
            // If there are no blahs, there's nothing to do
            if (blahsInGroupCount == 0) {
                String noBlahMsg = safe ? "No safe blahs in cohort id : " : "No blahs in cohort id : ";
                System.err.println(noBlahMsg + cohortId + " in group '" + getGroupName(groupId) + "' id : " + groupId);
                return 0;
            }

            // Sort blahs in memory, w.r.t. cohort-strength
            // from weak to strong
            Collections.sort(blahs, Collections.reverseOrder(new IsStrongerThan(cohortId)));


            addedInGroup += intBuildInbox(group, currentGenId, cohortId, blahs, lastInboxNumber, safe);
        }
        return addedInGroup;
    }

    private long intBuildInbox(DBObject group, String generationId, String cohortId, List<DBObject> blahs, long lastInboxNumber, Boolean safe) throws SystemErrorException, DBException, InterruptedException {

        final String groupId = group.get(BaseDAOConstants.ID).toString();

        final int blahsInGroupCount = blahs.size();

        // Calculate how many inboxes we need and initialize data
        Integer inboxCount = Utilities.getValueAsInteger(Math.ceil((blahsInGroupCount * 1.0d) / (MAX_BLAHS_PER_INBOX * 1.0d)), 0);
        if (inboxCount > 10)
            inboxCount = 10;

        final List<DBCollection> inboxCollections = new ArrayList<DBCollection>(inboxCount);
        final String inboxDbName = "inboxdb";

        for (int number = 0; number < inboxCount; number++) {

            final String inboxCollectionName =
                    CommonUtilities.makeInboxCollectionName(groupId, cohortId, number + lastInboxNumber + 1, safe);
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
            // use proper inbox shuffling

            int numHottest = 5;
            int numHot = 20;   // 25
            int numRecent = 0;  //55
            int numMedium = 50;     // 75
            int numCool = 15;   // 90
            int numBad = 10;    // 100
            int numNew = 0;
            long  minViews = 50;   // to do - should be based on author strength
            int inboxNumber = 0;
            int i = 0;

            // handle new blahs
            List<DBObject> newBlahs = new ArrayList<DBObject>();
            List<DBObject> recentBlahs = new ArrayList<DBObject>();
            Calendar currentDate = Calendar.getInstance();
            currentDate.add(Calendar.MONTH, -1);
            Date recentDate = currentDate.getTime();
            Date curDate;
            Object  tmp;

            for (Iterator<DBObject> itr = blahs.iterator();itr.hasNext();) {
                DBObject element = itr.next();
                tmp = element.get(BlahDAOConstants.VIEWS);
                if ((tmp == null) || ((Long)tmp < minViews)) {
                    newBlahs.add(element);
                } else {
                    curDate = (Date)element.get(BaseDAOConstants.CREATED);
                    if (curDate.compareTo(recentDate) > 0) {
                        recentBlahs.add(element);
                    }
                }
            }

            if (newBlahs.size() > 0) {
                int maxNew = 10; // up to 10 new blahs
                if (maxNew > newBlahs.size()) {
                    maxNew = newBlahs.size();
                }
                numNew = maxNew;
                numBad -= numNew;
            }

            if (recentBlahs.size() > 0) {
                int maxRecent = 30; // up to 30 new blahs
                if (maxRecent > recentBlahs.size()) {
                    maxRecent = recentBlahs.size();
                }
                numRecent = maxRecent;
                numMedium -= numRecent;
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
                        curBlahs.remove(newBlahList.remove(curBlahIndex)); // prevent dupes
                    }
                }

                if (numRecent > 0) {
                    List<DBObject>  recentBlahList = new ArrayList<DBObject>(recentBlahs);

                    for (i = 0; i < numRecent; i++) {
                        curBlahIndex = (int)(Math.random() * recentBlahList.size());
                        final BasicDBObject inboxItem = makeInboxItem(groupId, recentBlahList.get(curBlahIndex));
                        inboxCollections.get(inboxNumber).insert(inboxItem);
                        curBlahs.remove(recentBlahList.remove(curBlahIndex));   // prevent dupes
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
        final BasicDBObject query = new BasicDBObject("_id", new ObjectId(generationId));
        BasicDBObject setter;

        String firstKeyString;
        String lastKeyString;
        String keyString = "CHI." + cohortId + ".";
        if (safe) {
            firstKeyString = keyString + "FS";
            lastKeyString = keyString + "LS";
        } else {
            firstKeyString = keyString + "F";
            lastKeyString = keyString + "L";
        }

        lastInboxNumber = (lastInboxNumber == -1) ? 0 : (lastInboxNumber + 1);
        long firstInboxNumber = lastInboxNumber + inboxCount - 1;
        setter = new BasicDBObject(firstKeyString, lastInboxNumber);
        setter.put(lastKeyString, firstInboxNumber);

        /*
        // Update time created and amount of time it took to create it
        final Date lastCreated = (Date) group.get(GroupDAOConstants.LAST_TIME_INBOXES_GENERATED);
        final Date now = new Date();
        setter.put(GroupDAOConstants.LAST_TIME_INBOXES_GENERATED, now);
        if (lastCreated != null) {
            setter.put(GroupDAOConstants.INBOX_GENERATION_DURATION, now.getTime() - lastCreated.getTime());
        }
        */

        _generationInfoCol.update(query, new BasicDBObject("$set", setter));      // TODO use this in getInbox in rest

        String safeInboxWord = "inboxes";
        if (safe) safeInboxWord = "safe Inboxes";
        String msg = "Created " + blahsInGroupCount + " inbox items in group '"
                + getGroupName(groupId) + "' cohort id : " + cohortId + "  "
                + inboxCount + " new " + safeInboxWord + " in range: ["
                + lastInboxNumber + "," + firstInboxNumber + "]";

        Utilities.printit(true, msg);

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

        tmp = blah.get(BlahDAOConstants.BLAH_COHORT_STRENGTH); // cohort-strength
        if (tmp != null) {
            inboxItem.put(InboxBlahDAOConstants.BLAH_COHORT_STRENGTH, tmp);
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

    private List<DBObject> getAllBlahs(String groupId, boolean safe) throws DBException, InterruptedException {

        final BasicDBObject fieldsToReturn = makeBlahFieldsToReturn();

        BasicDBObject queryObj = new BasicDBObject(BlahDAOConstants.GROUP_ID, groupId).append("S", new BasicDBObject("$gte", 0));

        String findMsg = "finding all blahs in a group";
        if (safe) {
            ArrayList safeList = new ArrayList();
            safeList.add(new BasicDBObject(BlahDAOConstants.FLAGGEDCONTENT, new BasicDBObject("$exists", false)));
            safeList.add(new BasicDBObject(BlahDAOConstants.FLAGGEDCONTENT, false));
            queryObj.append("$or", safeList);

            findMsg = "finding all safe blahs in a group";
        }
        final DBCursor cursor = Utilities.findInDB(3, findMsg, _blahInfoCol, queryObj, fieldsToReturn);

        cursor.addOption(Bytes.QUERYOPTION_SLAVEOK);
        cursor.addOption(Bytes.QUERYOPTION_NOTIMEOUT);

        final List<DBObject> blahs = new ArrayList<DBObject>();
        while (cursor.hasNext()) {
            blahs.add(cursor.next());
        }
        return blahs;
    }

    // "relevant" blah has strong strength or was recently created
    // minStrength: have minimal strength to be "relevant" in this cohort
    // numDays: created within numDays days to be "relevant"
    private List<DBObject> getRelevantBlahs(String groupId, String cohortId, double minStrength, long numDays, boolean safe) throws DBException, InterruptedException {

        final BasicDBObject fieldsToReturn = makeBlahFieldsToReturn();
        ArrayList<BasicDBObject> orList = new ArrayList<BasicDBObject>();
        Date curDate = new Date();
        Date minDate = new Date(curDate.getTime() - numDays * 24 * 3600 * 1000 );
        // get blah's cohort-strength
        orList.add(new BasicDBObject("S."+cohortId, new BasicDBObject("$gt", minStrength)));
        orList.add(new BasicDBObject("c", new BasicDBObject("$gt", minDate)));

        BasicDBObject queryObj = new BasicDBObject(BlahDAOConstants.GROUP_ID, new ObjectId(groupId));

        String findMsg;
        if (safe) {
            ArrayList<BasicDBObject> safeList = new ArrayList<BasicDBObject>();
            safeList.add(new BasicDBObject(BlahDAOConstants.FLAGGEDCONTENT, new BasicDBObject("$exists", false)));
            safeList.add(new BasicDBObject(BlahDAOConstants.FLAGGEDCONTENT, false));

            ArrayList<BasicDBObject> andList = new ArrayList<BasicDBObject>();
            andList.add(new BasicDBObject("$or", orList));
            andList.add(new BasicDBObject("$or", safeList));

            queryObj.append("$and", andList);
            findMsg = "finding safe blahs in a group";
        } else {
            queryObj.append("$or", orList);
            findMsg = "finding blahs in a group";
        }

        final DBCursor cursor = Utilities.findInDB(3, findMsg, _blahInfoCol, queryObj, fieldsToReturn);

        cursor.addOption(Bytes.QUERYOPTION_SLAVEOK);
        cursor.addOption(Bytes.QUERYOPTION_NOTIMEOUT);

        final List<DBObject> blahs = new ArrayList<DBObject>();
        while (cursor.hasNext()) {
            blahs.add(cursor.next());
        }
        cursor.close();

        if (blahs.size() < 100)
            return getAllBlahs(groupId, safe);
        else
            return blahs;
    }

    /** Only these fields will be returned: be sure that this is consistent with buildInbox() */
    private BasicDBObject makeBlahFieldsToReturn() {
        final BasicDBObject fieldsToReturn = new BasicDBObject(BlahDAOConstants.BLAH_STRENGTH, 1);
        fieldsToReturn.put("T", 1);
        fieldsToReturn.put("Y", 1);
        fieldsToReturn.put("A", 1);
        fieldsToReturn.put("M", 1);
        fieldsToReturn.put("B", 1);
        //fieldsToReturn.put(BlahDAOConstants.BLAH_STRENGTH, 1); // duplicated
        fieldsToReturn.put("c", 1);
//        fieldsToReturn.put("V", 1);
        fieldsToReturn.put("XXX", 1);
//        fieldsToReturn.put(BlahDAOConstants.BLAH_COHORT_STRENGTH, 1); // cohort-strength
        return fieldsToReturn;
    }


    private class IsStrongerThan implements Comparator<DBObject> {
        public IsStrongerThan(String cohortId) {
            this.cohortId = cohortId;
        }
        private String cohortId;
        @Override
        public int compare(DBObject a, DBObject b) {
            BasicDBObject cohortStrengthA = (BasicDBObject) a.get("S");
            BasicDBObject cohortStrengthB = (BasicDBObject) b.get("S");
            final Double obj1 = (Double) cohortStrengthA.get(cohortId);
            final Double obj2 = (Double) cohortStrengthB.get(cohortId);
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
