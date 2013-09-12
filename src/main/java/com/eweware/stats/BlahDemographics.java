package main.java.com.eweware.stats;

import com.mongodb.*;
import main.java.com.eweware.service.base.i18n.LocaleId;
import main.java.com.eweware.service.base.store.dao.BaseDAOConstants;
import main.java.com.eweware.service.base.store.dao.DemographicsObjectDAOConstants;
import main.java.com.eweware.service.base.store.dao.UserBlahInfoDAOConstants;
import main.java.com.eweware.service.base.store.dao.schema.UserProfileSchema;
import main.java.com.eweware.stats.help.Utilities;
import org.bson.types.ObjectId;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * @author rk@post.harvard.edu
 *         Date: 9/26/12 Time: 6:42 PM
 */
public class BlahDemographics {

    private static Date thirtyDaysAgo = Utilities.getDateBeforeInDays(31);

    private DBCollection blahsCol;
    private DBCollection userBlahInfoCol;
    private DBCollection userProfilesCol;


    public BlahDemographics() throws Exception {
        this.blahsCol = DBCollections.getInstance().getBlahsCol();
        this.userBlahInfoCol = DBCollections.getInstance().getUserBlahInfoCol();
        this.userProfilesCol = DBCollections.getInstance().getUserProfilesCol();
    }

//    public static void main(String[] a) {
//        try {
//            long start = System.currentTimeMillis();
//            new BlahSummarizer().execute();
//            System.out.println(new Date() + ": BlahSummarizer took " + (System.currentTimeMillis() - start) + " ms");
//
//        } catch (Exception e) {
//            e.printStackTrace();
//            System.exit(-1);
//        }
//        System.exit(0);
//    }

    public long execute() throws Exception {

        final UserProfileSchema userProfileSchema = UserProfileSchema.getSchema(LocaleId.en_us);
        if (userProfileSchema == null) {
            throw new Exception("Missing user profile schema");
        }

        // Total blah aggregate
        Map<String, Map<String, ObjectInfo>> actionToBlahIdToBlahInfoMap = new HashMap<String, Map<String, ObjectInfo>>();
        Utilities.fillMapWithPossibleActions(actionToBlahIdToBlahInfoMap);

        // Last 30 days of blahs aggregate
        Map<String, Map<String, ObjectInfo>> last30DaysActionToBlahIdToBlahInfoMap = new HashMap<String, Map<String, ObjectInfo>>();
        Utilities.fillMapWithPossibleActions(last30DaysActionToBlahIdToBlahInfoMap);

        // Aggregate of blahs by group id (original blah group id)
        Map<String, Map<String, Map<String, ObjectInfo>>> groupIdToActionToBlahIdToBlahInfoMap = new HashMap<String, Map<String, Map<String, ObjectInfo>>>();
        Map<String, Map<String, Map<String, ObjectInfo>>> last30DaysGroupIdToActionToBlahIdToBlahInfoMap = new HashMap<String, Map<String, Map<String, ObjectInfo>>>();

        // Aggregate of blahs by blah type id
        Map<String, Map<String, Map<String, ObjectInfo>>> blahTypeIdToActionToBlahIdToBlahInfoMap = new HashMap<String, Map<String, Map<String, ObjectInfo>>>();
        Map<String, Map<String, Map<String, ObjectInfo>>> last30DaysBlahTypeIdToActionToBlahIdToBlahInfoMap = new HashMap<String, Map<String, Map<String, ObjectInfo>>>();

        computeFromUserBlahInfoData(userProfileSchema, actionToBlahIdToBlahInfoMap, last30DaysActionToBlahIdToBlahInfoMap, groupIdToActionToBlahIdToBlahInfoMap, last30DaysGroupIdToActionToBlahIdToBlahInfoMap, blahTypeIdToActionToBlahIdToBlahInfoMap, last30DaysBlahTypeIdToActionToBlahIdToBlahInfoMap);

        // Write data to blahs
        Utilities.printit(new Date() + ": Writing blah stats...");
        final long blahCount = Utilities.writeAggregateToObjects(blahsCol, actionToBlahIdToBlahInfoMap);

        // write aggregate
        Utilities.writeAggregate(DemographicsObjectDAOConstants.BLAH_DEMOGRAPHICS, actionToBlahIdToBlahInfoMap);
        Utilities.writeAggregate(DemographicsObjectDAOConstants.BLAH_30_DAYS_DEMOGRAPHICS, last30DaysActionToBlahIdToBlahInfoMap);
        writeAggregateForObjectId(DemographicsObjectDAOConstants.BLAHS_PER_GROUP_DEMOGRAPHICS, groupIdToActionToBlahIdToBlahInfoMap);
        writeAggregateForObjectId(DemographicsObjectDAOConstants.BLAHS_PER_GROUP_30_DAY_DEMOGRAPHICS, last30DaysGroupIdToActionToBlahIdToBlahInfoMap);
        writeAggregateForObjectId(DemographicsObjectDAOConstants.BLAHS_PER_BLAH_TYPE_DEMOGRAPHICS, blahTypeIdToActionToBlahIdToBlahInfoMap);
        writeAggregateForObjectId(DemographicsObjectDAOConstants.BLAHS_PER_BLAH_TYPE_30_DAY_DEMOGRAPHICS, last30DaysBlahTypeIdToActionToBlahIdToBlahInfoMap);

        return blahCount;
    }

    /**
     * Scan all user blah info records and fill in appropriate maps with actions recorded in each user record.
     * @param userProfileSchema
     * @param actionToBlahIdToBlahInfoMap
     * @param last30DaysActionToBlahIdToBlahInfoMap
     * @param groupIdToActionToBlahIdToBlahInfoMap
     * @param last30DaysGroupIdToActionToBlahIdToBlahInfoMap
     * @param blahTypeIdToActionToBlahIdToBlahInfoMap
     * @param last30DaysBlahTypeIdToActionToBlahIdToBlahInfoMap
     */
    private void computeFromUserBlahInfoData(UserProfileSchema userProfileSchema, Map<String, Map<String, ObjectInfo>> actionToBlahIdToBlahInfoMap, Map<String, Map<String, ObjectInfo>> last30DaysActionToBlahIdToBlahInfoMap, Map<String, Map<String, Map<String, ObjectInfo>>> groupIdToActionToBlahIdToBlahInfoMap, Map<String, Map<String, Map<String, ObjectInfo>>> last30DaysGroupIdToActionToBlahIdToBlahInfoMap, Map<String, Map<String, Map<String, ObjectInfo>>> blahTypeIdToActionToBlahIdToBlahInfoMap, Map<String, Map<String, Map<String, ObjectInfo>>> last30DaysBlahTypeIdToActionToBlahIdToBlahInfoMap) throws Exception {
        double totalUserBlahInfoCount = Utilities.getCountFromDB(3, "get blah info count", userBlahInfoCol, null);
        long tickInterval = Utilities.getValueAsLong(totalUserBlahInfoCount / 10d);
        long tickCount = 0;
        final DBCursor cursor = Utilities.findInDB(3, "find user blah info records", userBlahInfoCol, null, null);
        cursor.addOption(Bytes.QUERYOPTION_SLAVEOK);
        cursor.addOption(Bytes.QUERYOPTION_NOTIMEOUT);
        for (DBObject userBlahInfo : cursor) {

            if ((totalUserBlahInfoCount != 0) && (tickInterval != 0) && (++tickCount % tickInterval == 0))
                Utilities.printit(new Date() + ": Evaluated " + Math.floor((tickCount / totalUserBlahInfoCount) * 100) + "% (" + tickCount + "/" + totalUserBlahInfoCount + ") blah info...");

            Long vote = (Long) userBlahInfo.get(UserBlahInfoDAOConstants.PROMOTION);
            if (vote == null) {
                vote = 0L;
            }
            Long views = (Long) userBlahInfo.get(UserBlahInfoDAOConstants.VIEWS);
            if (views == null) {
                views = 0L;
            }
            Long opens = (Long) userBlahInfo.get(UserBlahInfoDAOConstants.OPENS);
            if (opens == null) {
                opens = 0L;
            }
            Long comments = (Long) userBlahInfo.get(UserBlahInfoDAOConstants.COMMENTS_ON_THIS_BLAH);
            if (comments == null) {
                comments = 0L;
            }

            final String userId = (String) userBlahInfo.get(UserBlahInfoDAOConstants.USER_ID);
            if (userId == null) {
                Utilities.printit(true, new Date() + ": WARNING: ignored userBlahInfo: userId missing from userBlahInfo id=" + userBlahInfo.get(BaseDAOConstants.ID));
                continue;
            }
            final DBObject userProfile = Utilities.findOneInDB(3, "finding a user profile record", userProfilesCol, new BasicDBObject(BaseDAOConstants.ID, new ObjectId(userId)), null);
            final String groupId = (String) userBlahInfo.get(UserBlahInfoDAOConstants.ORIGINAL_GROUP_ID);
            final String blahId = (String) userBlahInfo.get(UserBlahInfoDAOConstants.BLAH_ID);
            final String blahTypeId = (String) userBlahInfo.get(UserBlahInfoDAOConstants.BLAH_TYPE_ID);
            final boolean createdLessThanDaysAgo = Utilities.getCreatedDaysAgo((Date) userBlahInfo.get(BaseDAOConstants.CREATED), thirtyDaysAgo);

            // Total blah aggregate
            Utilities.computeDemographicsForAllActions(blahId, userProfileSchema, userProfile, actionToBlahIdToBlahInfoMap, vote, views, opens, comments);
            // Blah aggregate for last thirty days
            if (createdLessThanDaysAgo) {
                Utilities.computeDemographicsForAllActions(blahId, userProfileSchema, userProfile, last30DaysActionToBlahIdToBlahInfoMap, vote, views, opens, comments);
            }

            // Aggregate per group
            aggregatePerObjectId(groupId, blahId, userProfileSchema, userProfile, groupIdToActionToBlahIdToBlahInfoMap, vote, views, opens, comments);
            if (createdLessThanDaysAgo) {
                aggregatePerObjectId(groupId, blahId, userProfileSchema, userProfile, last30DaysGroupIdToActionToBlahIdToBlahInfoMap, vote, views, opens, comments);
            }

            // Aggregate per blah type
            aggregatePerObjectId(blahTypeId, blahId, userProfileSchema, userProfile, blahTypeIdToActionToBlahIdToBlahInfoMap, vote, views, opens, comments);
            if (createdLessThanDaysAgo) {
                aggregatePerObjectId(blahTypeId, blahId, userProfileSchema, userProfile, last30DaysBlahTypeIdToActionToBlahIdToBlahInfoMap, vote, views, opens, comments);
            }
        }
    }


    private void writeAggregateForObjectId(String idPrefix, Map<String, Map<String, Map<String, ObjectInfo>>> objectIdToActionToBlahIdToBlahInfoMap) throws Exception {
        for (Map.Entry<String, Map<String, Map<String, ObjectInfo>>> entry : objectIdToActionToBlahIdToBlahInfoMap.entrySet()) {
            final String objectId = entry.getKey();
            final Map<String, Map<String, ObjectInfo>> map = entry.getValue();
            Utilities.writeAggregate(idPrefix + objectId, map);
        }
    }

    private void aggregatePerObjectId(String objectId, String blahId, UserProfileSchema userProfileSchema, DBObject userProfile,
                                      Map<String, Map<String, Map<String, ObjectInfo>>> objectIdToActionToBlahIdToBlahInfoMap,
                                      Long vote, Long views, Long opens, Long comments) {
        Map<String, Map<String, ObjectInfo>> objectActionToBlahIdToBlahInfoMap = objectIdToActionToBlahIdToBlahInfoMap.get(objectId);
        if (objectActionToBlahIdToBlahInfoMap == null) {
            objectActionToBlahIdToBlahInfoMap = new HashMap<String, Map<String, ObjectInfo>>();
            Utilities.fillMapWithPossibleActions(objectActionToBlahIdToBlahInfoMap);
            objectIdToActionToBlahIdToBlahInfoMap.put(objectId, objectActionToBlahIdToBlahInfoMap);
        }
        Utilities.computeDemographicsForAllActions(blahId, userProfileSchema, userProfile, objectActionToBlahIdToBlahInfoMap, vote, views, opens, comments);
    }

}
