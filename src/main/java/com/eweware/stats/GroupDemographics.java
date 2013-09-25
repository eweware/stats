package com.eweware.stats;

import com.mongodb.*;
import com.eweware.DBException;
import com.eweware.service.base.CommonUtilities;
import com.eweware.service.base.i18n.LocaleId;
import com.eweware.service.base.payload.AuthorizedState;
import com.eweware.service.base.store.dao.BaseDAOConstants;
import com.eweware.service.base.store.dao.DemographicsObjectDAOConstants;
import com.eweware.service.base.store.dao.GroupDAOConstants;
import com.eweware.service.base.store.dao.UserGroupDAOConstants;
import com.eweware.service.base.store.dao.schema.UserProfileSchema;
import com.eweware.stats.help.Utilities;
import org.bson.types.ObjectId;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * @author rk@post.harvard.edu
 *         Date: 9/26/12 Time: 4:14 PM
 */
public class GroupDemographics {

    private DBCollection groupsCol;
    private DBCollection usergroupsCol;
    private DBCollection userProfilesCol;

//    public static void main(String[] a) {
//        try {
//            long start = System.currentTimeMillis();
//            new GroupSummarizer().execute();
//            System.out.println(new Date() + ": GroupSummarizer took " + (System.currentTimeMillis() - start) + " ms");
//
//        } catch (Exception e) {
//            e.printStackTrace();
//            System.exit(-1);
//        }
//        System.exit(0);
//    }

    public GroupDemographics() throws Exception {
        this.groupsCol = DBCollections.getInstance().getGroupsCol();
        this.userProfilesCol = DBCollections.getInstance().getUserProfilesCol();
        this.usergroupsCol = DBCollections.getInstance().getUserGroupsCol();
    }

    // TODO WRS-102 Demographic breakdown of most active group members (top 10% using (# comments + # blahs)

    /**
     * @return <p>Returns the number of groups processed.</p>
     * @throws Exception
     */
    public long execute() throws Exception {

        final UserProfileSchema userProfileSchema = UserProfileSchema.getSchema(LocaleId.en_us);
        if (userProfileSchema == null) {
            throw new Exception("Missing user profile schema");
        }

        // Maps a group id to its active member count
        Map<String, Long> groupIdToActiveMemberCount = new HashMap<String, Long>();

        // Maps a group id to a map of demographic field name and count in that field
        // key: groupId
        // value: map of demo field name (e.g., "g" for gender) to map of demo field values (e.g., "0" for male) to member count with the value
        Map<String, Map<String, Map<String, Long>>> groupIdToActiveMemberDemographicsCount = new HashMap<String, Map<String, Map<String, Long>>>();
        final String active = AuthorizedState.A.toString();

        // Gather stats on a per-user-per-group basis
//        double totalCount = Utilities.getCountFromDB(3, "get user group record count", usergroupsCol, null);
//        long tickInterval = CommonUtilities.getValueAsLong(totalCount / 10);
//        int tickCount = 0;
        final DBCursor cursor = Utilities.findInDB(3, "find all user group records", usergroupsCol, null, null);

        cursor.addOption(Bytes.QUERYOPTION_SLAVEOK);
        cursor.addOption(Bytes.QUERYOPTION_NOTIMEOUT);

        for (DBObject usergroup : cursor) {

//            if ((totalCount != 0) && (tickInterval != 0) && (++tickCount % tickInterval == 0) && (totalCount != 0)) {
//                Utilities.printit(new Date() + ": Evaluated " + Math.floor((tickCount / totalCount) * 100) + "% (" + tickCount + "/" + totalCount + ") user group info...");
//            }

            final String userId = (String) usergroup.get(UserGroupDAOConstants.USER_ID);
            final String groupId = (String) usergroup.get(UserGroupDAOConstants.GROUP_ID);
            final String state = (String) usergroup.get(UserGroupDAOConstants.STATE);

            final boolean userIsActive = state.equals(active);
            if (userIsActive) {
                Long count = groupIdToActiveMemberCount.get(groupId);
                groupIdToActiveMemberCount.put(groupId, (count == null) ? 1L : count + 1L);
            }
            final DBObject userProfile = Utilities.findOneInDB((3), "finding a user profile record", userProfilesCol, new BasicDBObject(BaseDAOConstants.ID, new ObjectId(userId)), null);

            Map<String, Map<String, Long>> fieldNameToFieldValueToCountMap = new HashMap<String, Map<String, Long>>();
            final long increment = 1;
            Utilities.computeDemographics(userProfileSchema, userProfile, fieldNameToFieldValueToCountMap, increment);

            final Map<String, Map<String, Long>> lastFieldNameToFieldValueToCountMap = groupIdToActiveMemberDemographicsCount.get(groupId);
            if (lastFieldNameToFieldValueToCountMap == null) {
                groupIdToActiveMemberDemographicsCount.put(groupId, fieldNameToFieldValueToCountMap);
            } else {
                Utilities.aggregateDemoFieldValueCounts(lastFieldNameToFieldValueToCountMap, fieldNameToFieldValueToCountMap);
            }
        }


        // Write data to groups
        return writeDataToGroupObjects(groupIdToActiveMemberCount, groupIdToActiveMemberDemographicsCount);

    }

    private long writeDataToGroupObjects(Map<String, Long> groupIdToActiveMemberCount, Map<String, Map<String, Map<String, Long>>> groupIdToActiveMemberDemographicsCount) throws DBException, InterruptedException {
        long groupCount = 0L;
        final DBCursor cursor = Utilities.findInDB(3, "finding all group records", groupsCol, null, null);
        for (DBObject group : cursor) {
            groupCount++;
            final ObjectId groupIdAsObjectId = (ObjectId) group.get(BaseDAOConstants.ID);
            final String groupId = groupIdAsObjectId.toString();
            final Map<String, Map<String, Long>> counts = groupIdToActiveMemberDemographicsCount.get(groupId);
            final Long activeMemberCount = groupIdToActiveMemberCount.get(groupId);
            if (counts == null) {
                final BasicDBObject update = new BasicDBObject("$unset", new BasicDBObject(DemographicsObjectDAOConstants.DEMOGRAPHICS_RECORD, 1));
                if (activeMemberCount != null) {
                    update.put("$set", new BasicDBObject(GroupDAOConstants.USER_COUNT, activeMemberCount));
                }
                groupsCol.update(new BasicDBObject(BaseDAOConstants.ID, groupIdAsObjectId), update);
            } else {
                final BasicDBObject setters = new BasicDBObject(DemographicsObjectDAOConstants.DEMOGRAPHICS_RECORD, counts);
                final BasicDBObject update = new BasicDBObject("$set", setters);
                if (activeMemberCount != null) {
                    setters.put(GroupDAOConstants.USER_COUNT, activeMemberCount);
                }
                groupsCol.update(new BasicDBObject(BaseDAOConstants.ID, groupIdAsObjectId), update);
            }
        }
        return groupCount;
    }

}
