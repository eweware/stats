package main.java.com.eweware.stats;

import com.mongodb.*;
import main.java.com.eweware.service.base.CommonUtilities;
import main.java.com.eweware.service.base.i18n.LocaleId;
import main.java.com.eweware.service.base.store.dao.DemographicsObjectDAOConstants;
import main.java.com.eweware.service.base.store.dao.UserCommentInfoDAO;
import main.java.com.eweware.service.base.store.dao.UserProfileDAO;
import main.java.com.eweware.service.base.store.dao.schema.UserProfileSchema;
import main.java.com.eweware.stats.help.Utilities;
import org.bson.types.ObjectId;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * @author rk@post.harvard.edu
 *         Date: 9/29/12 Time: 12:52 AM
 */
public class CommentDemographics {

    private static Date thirtyDaysAgo = Utilities.getDateBeforeInDays(31);

    private DBCollection commentsCol;
    private DBCollection userCommentInfoCol;
    private DBCollection userProfilesCol;


    public CommentDemographics() throws Exception {
        this.commentsCol = DBCollections.getInstance().getCommentsCol();
        this.userCommentInfoCol = DBCollections.getInstance().getUserCommentInfoCol();
        this.userProfilesCol = DBCollections.getInstance().getUserProfilesCol();
    }

    public static void main(String[] a) {
        try {
            long start = System.currentTimeMillis();
            new CommentDemographics().execute();
            Utilities.printit(new Date() + ": CommentSummarizer took " + (System.currentTimeMillis() - start) + " ms");

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }
        System.exit(0);
    }

    public long execute() throws Exception {

        final UserProfileSchema userProfileSchema = UserProfileSchema.getSchema(LocaleId.en_us);
        if (userProfileSchema == null) {
            throw new Exception("Missing user profile schema");
        }

        Map<String, Map<String, ObjectInfo>> actionToCommentIdToCommentInfoMap = new HashMap<String, Map<String, ObjectInfo>>();
        Map<String, Map<String, ObjectInfo>> last30DaysActionToCommentIdToCommentInfoMap = new HashMap<String, Map<String, ObjectInfo>>();
        for (String action : new String[]{DemographicsObjectDAOConstants.UP_VOTE_COUNT, DemographicsObjectDAOConstants.DOWN_VOTE_COUNT, DemographicsObjectDAOConstants.VIEW_COUNT, DemographicsObjectDAOConstants.OPEN_COUNT}) {
            actionToCommentIdToCommentInfoMap.put(action, new HashMap<String, ObjectInfo>());
            last30DaysActionToCommentIdToCommentInfoMap.put(action, new HashMap<String, ObjectInfo>());
        }

        double totalCount = Utilities.getCountFromDB(3, "get comment info count", userCommentInfoCol, null);
        long tickInterval = CommonUtilities.getValueAsLong(totalCount / 10d);
        int tickCount = 0;
        final DBCursor cursor = Utilities.findInDB(3, "finding all user comment info records", userCommentInfoCol, null, null);

        cursor.addOption(Bytes.QUERYOPTION_SLAVEOK);
        cursor.addOption(Bytes.QUERYOPTION_NOTIMEOUT);

        for (DBObject userCommentInfo : cursor) {

//            if (totalCount != 0 && (++tickCount % tickInterval == 0)) {
//                Utilities.printit(new Date() + ": Evaluated " + Math.floor((tickCount / totalCount) * 100) + "% (" + tickCount + "/" + totalCount + ") comment info...");
//            }

            Long vote = (Long) userCommentInfo.get(UserCommentInfoDAO.VOTE);
            if (vote == null) {
                vote = 0L;
            }
            Long views = (Long) userCommentInfo.get(UserCommentInfoDAO.VIEWS);
            if (views == null) {
                views = 0L;
            }
            Long opens = (Long) userCommentInfo.get(UserCommentInfoDAO.OPENS);
            if (opens == null) {
                opens = 0L;
            }

            final String userId = (String) userCommentInfo.get(UserCommentInfoDAO.USER_ID);
            final String commentId = (String) userCommentInfo.get(UserCommentInfoDAO.COMMENT_ID);
            final DBObject userProfile = Utilities.findOneInDB(3, "finding a user profile record", userProfilesCol, new BasicDBObject(UserProfileDAO.ID, new ObjectId(userId)), null);
            Utilities.computeDemographicsForAllActions(commentId, userProfileSchema, userProfile, actionToCommentIdToCommentInfoMap, vote, views, opens, 0L);
            if (Utilities.getCreatedDaysAgo((Date) userCommentInfo.get(UserCommentInfoDAO.CREATED), thirtyDaysAgo)) {
                Utilities.computeDemographicsForAllActions(commentId, userProfileSchema, userProfile, last30DaysActionToCommentIdToCommentInfoMap, vote, views, opens, 0L);
            }
        }

        // Write data to comments
        Utilities.printit(new Date() + ": Writing comment stats...");
        final long commentCount = Utilities.writeAggregateToObjects(commentsCol, actionToCommentIdToCommentInfoMap);

        // write aggregate
        Utilities.writeAggregate(DemographicsObjectDAOConstants.COMMENTS_DEMOGRAPHICS, actionToCommentIdToCommentInfoMap);
        Utilities.writeAggregate(DemographicsObjectDAOConstants.COMMENTS_30_DAYS_DEMOGRAPHICS, last30DaysActionToCommentIdToCommentInfoMap);

        return commentCount;
    }
}
