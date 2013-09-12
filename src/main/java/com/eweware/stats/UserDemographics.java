package main.java.com.eweware.stats;

import com.mongodb.*;
import main.java.com.eweware.ApplicationException;
import main.java.com.eweware.DBException;
import main.java.com.eweware.service.base.i18n.LocaleId;
import main.java.com.eweware.service.base.store.dao.*;
import main.java.com.eweware.service.base.store.dao.schema.SchemaSpec;
import main.java.com.eweware.service.base.store.dao.schema.UserProfileSchema;
import main.java.com.eweware.service.base.store.dao.schema.type.SchemaDataType;
import main.java.com.eweware.stats.help.Utilities;
import org.bson.types.ObjectId;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * @author rk@post.harvard.edu
 *         Date: 8/29/12 Time: 10:31 AM
 */
public class UserDemographics implements UserTrackerDAOConstants {

    private static final Long ONE_LONG = new Long(1);
    private DBCollection blahsCol;
    private DBCollection usersCol;
    private DBCollection userBlahInfoCol;
    private DBCollection userProfilesCol;

    public static void main(String[] a) {
        try {

            long start = System.currentTimeMillis();
            new UserDemographics().execute();
            if (Main._verbose) {
                System.out.println(new Date() + ": UserSummarizer took " + (System.currentTimeMillis() - start) + " ms");
            }

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }
        System.exit(0);
    }

    public UserDemographics() throws Exception {
        this.usersCol = DBCollections.getInstance().getUsersCol();
        this.userBlahInfoCol = DBCollections.getInstance().getUserBlahInfoCol();
        this.blahsCol = DBCollections.getInstance().getBlahsCol();
        this.userProfilesCol = DBCollections.getInstance().getUserProfilesCol();
    }

    /**
     * Consider blahs created by user
     * For each other user:
     * gather demographics for upvote, downvote, views, opens, created comments:
     * one set of demos for a per-user count: e.g., count >= 1 views as 1 view, etc
     * one set of demos for a per-action count: e.g., count N views as N views
     *
     * @throws Exception
     */
    public long execute() throws Exception {

        final UserProfileSchema userProfileSchema = UserProfileSchema.getSchema(LocaleId.en_us);
        if (userProfileSchema == null) {
            throw new Exception("Missing user profile schema");
        }

        final DBObject userFieldsToFetch = new BasicDBObject(BaseDAOConstants.ID, 1);
        final DBObject userBlahFieldsToFetch = new BasicDBObject(UserBlahInfoDAOConstants.BLAH_ID, 1);
        userBlahFieldsToFetch.put(UserBlahInfoDAO.UPDATED, 1);
        final Date cutoffDate = Utilities.getDateBeforeInDays(7); // get last week's stats

        // Aggregate of blahs by author id
        final DBCursor userCursor = Utilities.findInDB(3, "finding all user records", usersCol, null, userFieldsToFetch);

        userCursor.addOption(Bytes.QUERYOPTION_SLAVEOK);
        userCursor.addOption(Bytes.QUERYOPTION_NOTIMEOUT);

        long userCount = 0L;
        for (final DBObject user : userCursor) {

            final ObjectId userObjectId = (ObjectId) user.get(BaseDAOConstants.ID);
            final String userId = userObjectId.toString();

            // Make demo template for this user
            final Map<String, Map<String, Map<String, Object>>> actionToFieldNameToFieldValueToCountMap = new HashMap<String, Map<String, Map<String, Object>>>();
            fillMapWithPossibleActions(actionToFieldNameToFieldValueToCountMap, userProfileSchema);
            final Map<String, Map<String, Map<String, Object>>> recentActionToFieldNameToFieldValueToCountMap = new HashMap<String, Map<String, Map<String, Object>>>();
            fillMapWithPossibleActions(recentActionToFieldNameToFieldValueToCountMap, userProfileSchema);

            computeAndWriteForBlahUser(userProfileSchema, userBlahFieldsToFetch, userObjectId, userId,
                    actionToFieldNameToFieldValueToCountMap, recentActionToFieldNameToFieldValueToCountMap,
                    cutoffDate);
            userCount++;
        }
        return userCount;
    }

    /**
     * <p>Collects all </p>
     * @param userProfileSchema
     * @param userBlahFieldsToFetch
     * @param authorIdObjectId
     * @param authorId
     * @param actionToFieldNameToFieldValueToCountMap
     * @param recentActionToFieldNameToFieldValueToCountMap
     * @param cutoffDate
     * @throws DBException
     * @throws InterruptedException
     */
    private void computeAndWriteForBlahUser(UserProfileSchema userProfileSchema, DBObject userBlahFieldsToFetch,
                                            ObjectId authorIdObjectId, String authorId,
                                            Map<String, Map<String, Map<String, Object>>> actionToFieldNameToFieldValueToCountMap,
                                            Map<String, Map<String, Map<String, Object>>> recentActionToFieldNameToFieldValueToCountMap,
                                            Date cutoffDate) throws DBException, InterruptedException, ApplicationException {

        // Get all blahs by the authorId
        final DBCursor blahsByAuthorCursor = Utilities.findInDB(3, "finding some blah records", blahsCol, new BasicDBObject(BlahDAOConstants.AUTHOR_ID, authorId), userBlahFieldsToFetch);

        blahsByAuthorCursor.addOption(Bytes.QUERYOPTION_SLAVEOK);
        blahsByAuthorCursor.addOption(Bytes.QUERYOPTION_NOTIMEOUT);

        // Iterate through each blah by authorId
        for (final DBObject authoredBlah : blahsByAuthorCursor) {

            // Get all blah/user info records for this blah
            final DBCursor userBlahInfoCursor = Utilities.findInDB(3, "finding a user's blah records", userBlahInfoCol, new BasicDBObject(UserBlahInfoDAOConstants.BLAH_ID, authoredBlah.get(BlahDAO.ID).toString()), null);

            // Iterate through each blah/user record
            for (final DBObject userBlahInfoDAO : userBlahInfoCursor) {

                final String interestedUserId = (String) userBlahInfoDAO.get(UserBlahInfoDAOConstants.USER_ID);
                if (interestedUserId.equals(authorId)) {
                    continue; // ignore: disregard user's activity on own authored blah
                }

                // We've got a user who has acted on a blah by the author: get the profile data from the user
                final DBObject userProfile = Utilities.findOneInDB(3, "finding a user profile record", userProfilesCol, new BasicDBObject(UserProfileDAO.ID, new ObjectId(interestedUserId)), null);

                computeDemoForUser(userProfileSchema, userProfile, actionToFieldNameToFieldValueToCountMap, userBlahInfoDAO);

                final Date updated = (Date) userBlahInfoDAO.get(UserBlahInfoDAO.UPDATED);
                if (updated != null && updated.after(cutoffDate)) {
                    computeDemoForUser(userProfileSchema, userProfile, recentActionToFieldNameToFieldValueToCountMap, userBlahInfoDAO);
                }
            }
        }
        writeDemoForUser(authorIdObjectId, actionToFieldNameToFieldValueToCountMap, false);
        writeDemoForUser(authorIdObjectId, recentActionToFieldNameToFieldValueToCountMap, true);
    }

    private void writeDemoForUser(ObjectId authorId, Map<String, Map<String, Map<String, Object>>> actionToFieldNameToFieldValueToCountMap, boolean recentDemographics) {
        final DBObject data = new BasicDBObject(recentDemographics ? DemographicsObjectDAOConstants.RECENT_DEMOGRAPHICS_RECORD : DemographicsObjectDAOConstants.DEMOGRAPHICS_RECORD,
                actionToFieldNameToFieldValueToCountMap);
        final DBObject update = new BasicDBObject("$set", data);
        usersCol.update(new BasicDBObject(UserDAO.ID, authorId), update);
    }

    private void fillMapWithPossibleActions(Map<String, Map<String, Map<String, Object>>> actionToFieldNameToFieldValueToCountMap, UserProfileSchema userProfileSchema) {
        for (String action : DemographicsObjectDAOConstants.DEMOGRAPHICS_RECORD_FIELD_NAMES) {
            final HashMap<String, Map<String, Object>> fieldNameToFieldValueToCountMap = new HashMap<String, Map<String, Object>>();
            actionToFieldNameToFieldValueToCountMap.put(action, fieldNameToFieldValueToCountMap);
        }
    }

    private void computeDemoForUser(UserProfileSchema userProfileSchema, DBObject userProfile, Map<String, Map<String, Map<String, Object>>> actionToFieldNameToFieldValueToCountMap, DBObject userBlahInfoDAO) throws ApplicationException {

        // Get action details from user blah info record
        Long vote = (Long) userBlahInfoDAO.get(UserBlahInfoDAOConstants.PROMOTION);
        vote = (vote == null) ? 0L : vote;
        Long views = (Long) userBlahInfoDAO.get(UserBlahInfoDAOConstants.VIEWS);
        views = (views == null) ? 0L : (views > 0L) ? 1L : 0L;
        Long opens = (Long) userBlahInfoDAO.get(UserBlahInfoDAOConstants.OPENS);
        opens = (opens == null) ? 0L : (opens > 0L) ? 1L : 0L;
        Long comments = (Long) userBlahInfoDAO.get(UserBlahInfoDAOConstants.COMMENTS_ON_THIS_BLAH);
        comments = (comments == null) ? 0L : (comments > 0L) ? 1L : 0L;

        final Map<Object, Boolean> userIdHasEmptyDemoFieldMap = new HashMap<Object, Boolean>();

        // Iterate for each action
        for (Map.Entry<String, Map<String, Map<String, Object>>> entry : actionToFieldNameToFieldValueToCountMap.entrySet()) {

            final String action = entry.getKey();
            Map<String, Map<String, Object>> fieldNameToFieldValueToCounterObjectMap = entry.getValue();
            if (fieldNameToFieldValueToCounterObjectMap == null) {
                fieldNameToFieldValueToCounterObjectMap = new HashMap<String, Map<String, Object>>();
                entry.setValue(fieldNameToFieldValueToCounterObjectMap);
            }

            for (Map.Entry<String, SchemaSpec> fieldNameToSchemaSpecMap : userProfileSchema.getFieldNameToSpecMap().entrySet()) {

                final String fieldName = fieldNameToSchemaSpecMap.getKey();  // e.g., g for gender
                final SchemaSpec spec = fieldNameToSchemaSpecMap.getValue();
                final SchemaDataType dataType = spec.getDataType();

                if (dataType == SchemaDataType.ILS || dataType == SchemaDataType.ILN) { // ignore others
                    Map<String, Object> fieldValueToCountMap = fieldNameToFieldValueToCounterObjectMap.get(fieldName);
                    if (fieldValueToCountMap == null) {
                        fieldValueToCountMap = new HashMap<String, Object>();
                        fieldNameToFieldValueToCounterObjectMap.put(fieldName, fieldValueToCountMap);
                    }
                    String profileFieldValue = (String) ((userProfile == null) ? spec.getDefaultValue() : userProfile.get(fieldName));
                    if (profileFieldValue == null || !(profileFieldValue instanceof String)) {
                        if (userProfile != null) {
                            if (userIdHasEmptyDemoFieldMap.get(userProfile.get(UserProfileDAO.ID)) == Boolean.TRUE) {
                                continue;
                            }
                            userIdHasEmptyDemoFieldMap.put(userProfile.get(UserProfileDAO.ID), Boolean.TRUE);
                        }
                        Utilities.printitNoReturn(new Date() + ": WARNING: demographics field value="+profileFieldValue+" is not a String for fieldName=" + fieldName);
                        if (userProfile == null) {
                            Utilities.printit();
                        } else {
                            Utilities.printit(" userId=" + userProfile.get(UserProfileDAO.ID));
                        }
                        continue;
                    }
                    final Object ct = fieldValueToCountMap.get(profileFieldValue);
                    if (ct != null && !(ct instanceof Long)) {
                        throw new ApplicationException("Expected Long but got " + ct.getClass().getName() + " in object=" + ct);
                    }
                    final Long count = (Long) ct;
                    if (action.equals(DemographicsObjectDAOConstants.OPEN_COUNT)) {
                        if (opens > 0L) {
                            fieldValueToCountMap.put(profileFieldValue, (count == null) ? opens : count + opens);
                        }
                    }   else if (action.equals(DemographicsObjectDAOConstants.VIEW_COUNT)) {
                        if (views > 0L) {
                            fieldValueToCountMap.put(profileFieldValue, (count == null) ? views : count + views);
                        }
                    }   else if (action.equals(DemographicsObjectDAOConstants.UP_VOTE_COUNT)) {
                        if (vote > 0L) {
                            fieldValueToCountMap.put(profileFieldValue, (count == null) ? 1L : count + 1L);
                        }
                    }  else if (action.equals(DemographicsObjectDAOConstants.DOWN_VOTE_COUNT)) {
                        if (vote < 0L) {
                            fieldValueToCountMap.put(profileFieldValue, (count == null) ? 1L : count + 1L);
                        }
                    }  else if (action.equals(DemographicsObjectDAOConstants.COMMENT_COUNT)) {
                        if (comments > 0L) {
                            fieldValueToCountMap.put(profileFieldValue, (count == null) ? comments : count + comments);
                        }
                    }
                } else if (fieldName.equals(UserProfileDAOConstants.USER_PROFILE_DATE_OF_BIRTH)) {
                    Map<String, Object> fieldValueToBucketsMap = fieldNameToFieldValueToCounterObjectMap.get(fieldName);
                    if (fieldValueToBucketsMap == null) {
                        fieldValueToBucketsMap = new HashMap<String, Object>();
                        fieldNameToFieldValueToCounterObjectMap.put(fieldName, fieldValueToBucketsMap);
                    }
                    if (userProfile == null) {
                        addUnspecifiedCountForAction(vote, views, opens, comments, action, fieldValueToBucketsMap);
                    } else {
                        final Object dateOfBirth = userProfile.get(fieldName);
                        if (dateOfBirth != null && dateOfBirth instanceof Date) {
                            final Date dob = (Date) dateOfBirth;
                            final int age = Utilities.getAgeInYears(dob);
                            final String bucket = Utilities.getAgeBucket(age, Utilities.UNSPECIFIED_AGE_BUCKET_FIELDNAME);
                            final Object currCt = fieldValueToBucketsMap.get(bucket);
                            if (currCt != null && !(currCt instanceof Long)) {
                                throw new ApplicationException("Expected Long but got " + currCt.getClass().getName());
                            }
                            final Long currentCount = (Long) currCt;
                            updateCountForAction(vote, views, opens, comments, action, fieldValueToBucketsMap, bucket, currentCount);
                        } else { // unspecified
                            addUnspecifiedCountForAction(vote, views, opens, comments, action, fieldValueToBucketsMap);
                        }
                    }
                }
            }
        }
    }

    private void addUnspecifiedCountForAction(Long vote, Long views, Long opens, Long comments, String action, Map<String, Object> fieldValueToBucketsMap) throws ApplicationException {
        final Object currCt = fieldValueToBucketsMap.get(Utilities.UNSPECIFIED_AGE_BUCKET_FIELDNAME);
        if (currCt != null && !(currCt instanceof Long)) {
            throw new ApplicationException("Expected Long but got " + currCt.getClass().getName());
        }
        final Long currentCount = (Long) currCt;
        updateCountForAction(vote, views, opens, comments, action, fieldValueToBucketsMap, Utilities.UNSPECIFIED_AGE_BUCKET_FIELDNAME, currentCount);
    }

    private void updateCountForAction(Long vote, Long views, Long opens, Long comments, String action, Map<String, Object> fieldValueToBucketsMap, String bucket, Long currentCount) {
        if (action.equals(DemographicsObjectDAOConstants.OPEN_COUNT)) {
            if (opens > 0) {
                fieldValueToBucketsMap.put(bucket, (currentCount == null) ? opens : currentCount + opens);
            }
        }   else if (action.equals(DemographicsObjectDAOConstants.VIEW_COUNT)) {
            if (views > 0) {
                fieldValueToBucketsMap.put(bucket, (currentCount == null) ? views : currentCount + views);
            }
        }   else if (action.equals(DemographicsObjectDAOConstants.UP_VOTE_COUNT)) {
            if (vote > 0) {
                fieldValueToBucketsMap.put(bucket, (currentCount == null) ? ONE_LONG : currentCount + ONE_LONG);
            }
        }  else if (action.equals(DemographicsObjectDAOConstants.DOWN_VOTE_COUNT)) {
            if (vote < 0) {
                fieldValueToBucketsMap.put(bucket, (currentCount == null) ? ONE_LONG : currentCount + ONE_LONG);
            }
        }  else if (action.equals(DemographicsObjectDAOConstants.COMMENT_COUNT)) {
            if (comments > 0) {
                fieldValueToBucketsMap.put(bucket, (currentCount == null) ? new Long(comments) : currentCount + new Long(comments));
            }
        }
    }

}
