package main.java.com.eweware.stats;

import cern.colt.list.DoubleArrayList;
import cern.jet.stat.Descriptive;
import com.mongodb.*;
import main.java.com.eweware.service.base.CommonUtilities;
import main.java.com.eweware.service.base.store.dao.*;
import main.java.com.eweware.service.base.store.impl.mongo.dao.MongoStoreManager;
import main.java.com.eweware.stats.help.Utilities;
import org.bson.types.ObjectId;

import java.util.*;
import java.util.logging.Logger;

/**
 * @author rk@post.harvard.edu
 *         Date: 9/30/12 Time: 6:09 PM
 */
public class UserDescriptiveStats implements UserTrackerDAOConstants {

    private static final Logger logger = Logger.getLogger(UserDescriptiveStats.class.getName());

    private static final String FIRST_YEAR_MONTH_SINCE_BEGINNING_OF_BLAHGUA = "1200";
    private static final int DAY_OF_MONTH_SINCE_BEGINNING_OF_BLAHGUA = 14;


    // Collections
    private DBCollection userTrackerCollection;

    // TODO could persist in disc
    /* Maps a fieldName to all dailies */
    final Map<String, Data> overallFieldNameToAllUsersDailiesMap = new HashMap<String, Data>();

    Map<String, Data> fieldNameToDailiesMap;


    private boolean doUpdate = true;

    public void execute() throws Exception {
        userTrackerCollection = DBCollections.getInstance().getTrackUserCol();
        final DBCollection usersCol = DBCollections.getInstance().getUsersCol();

//        final long userTrackerCount = Utilities.getCountFromDB(3, "get user tracker count", userTrackerCollection, null);
//        Utilities.printit((new Date() + ": Found " + userTrackerCount + " trackers").toString());

        int userCount = 0;
        int trackerCount = 0;
        String lastUserId = null;
        final DBCursor cursor = userTrackerCollection.find().sort(new BasicDBObject(UserTrackerDAO.UT_USER_ID, 1));

        cursor.addOption(Bytes.QUERYOPTION_SLAVEOK);
        cursor.addOption(Bytes.QUERYOPTION_NOTIMEOUT);

        boolean haveUsers = false;
        for (DBObject userTracker : cursor) {

            haveUsers = true;

            final String trackerId = (String) userTracker.get(UserTrackerDAO.ID);
            final String userId = (String) userTracker.get(UserTrackerDAO.UT_USER_ID);

            final int lastValidDayOfMonth = getLastValidDayOfMonthZeroOrigin(trackerId);

            if (lastUserId == null || !lastUserId.equals(userId)) { // New user

                final DBObject user = Utilities.findOneInDB(3, "finding a user record", usersCol, new BasicDBObject(UserDAO.ID, MongoStoreManager.makeObjectId(userId)), null);
                if (user == null) {
                    Utilities.printit(true, new Date() + ": " + ("userId=" + userId + " not found: deleted user?").toString());
                    continue;
                }

                userCount++;

                if (lastUserId != null) { // aggregate user's dailies
                    updateUserDailies();
                }

                // Initialize field name -> dailies map for new user
                fieldNameToDailiesMap = new LinkedHashMap<String, Data>();
                for (String fieldName : UserTrackerDAOConstants.UT_DAILY_FIELD_NAMES) {
                    fieldNameToDailiesMap.put(fieldName, new Data());
                }
                lastUserId = userId;
            }
            computeTrackerStatsAndUpdateTracker(userTracker, trackerId, lastValidDayOfMonth);
            trackerCount++;
        }

        if (haveUsers) {
            updateUserDailies(); // complete last scanned user

            computeOverallStatistics();

            updateTrackersForOverallStats();

            Utilities.printit((new Date() + ": Done: " + userCount + " users; " + trackerCount + " trackers").toString());

            updateUserStrengths();
        }

    }

    private void updateUserDailies() {
        if (fieldNameToDailiesMap != null) {
            for (Map.Entry<String, Data> entry : fieldNameToDailiesMap.entrySet()) {
                Data allDailies = overallFieldNameToAllUsersDailiesMap.get(entry.getKey());
                if (allDailies == null) {
                    allDailies = new Data();
                    overallFieldNameToAllUsersDailiesMap.put(entry.getKey(), allDailies);
                }
                final DoubleArrayList dailiesForThisUser = entry.getValue().dailies;
                allDailies.dailies.addAllOf(dailiesForThisUser);
            }
        }
    }

    private void computeTrackerStatsAndUpdateTracker(DBObject userTracker, String trackerId, int lastValidDayOfMonth) {
        final BasicDBList dailies = (BasicDBList) userTracker.get(UserTrackerDAO.UT_DAILY_STATS_ARRAY);
        List<Object> filteredDailies = new ArrayList<Object>();
        final String userTrackerDate = Utilities.getUserTrackerDate(trackerId);
        final boolean isMonthSinceBeginningOfBlahgua = userTrackerDate.startsWith(FIRST_YEAR_MONTH_SINCE_BEGINNING_OF_BLAHGUA);
        for (int day = 0; day < dailies.size(); day++) {

            final int dayofmonth = DAY_OF_MONTH_SINCE_BEGINNING_OF_BLAHGUA - 1; // 0-origin
            if (isMonthSinceBeginningOfBlahgua && day < dayofmonth) {
                continue;
            }
            if (day > lastValidDayOfMonth) {
                break;
            }
            final Object daily = dailies.get(day);
            filteredDailies.add(daily);

        }
        registerData(filteredDailies);

        final DBObject updates = computeUserStatistics();
        if (doUpdate) {
            updateTracker(updates, trackerId);
        }
    }

    private void updateUserStrengths() throws Exception {

        Utilities.printit(new Date() + ": Updating user strengths...");
        final DBCollection usersCollection = DBCollections.getInstance().getUsersCol();
        final DBCollection blahsCollection = DBCollections.getInstance().getBlahsCol();

        final Map<ObjectId, Double> userIdToTotalBlahStrength = new HashMap<ObjectId, Double>();
        final Map<ObjectId, Double> userIdToTotalBlahControversialStrength = new HashMap<ObjectId, Double>();
        final DoubleArrayList userStrengths = new DoubleArrayList();
        final DoubleArrayList userControversyStrengths = new DoubleArrayList();

        final DBCursor userCursor = Utilities.findInDB(3, "finding all user records", usersCollection, null, null);

        userCursor.addOption(Bytes.QUERYOPTION_SLAVEOK);
        userCursor.addOption(Bytes.QUERYOPTION_NOTIMEOUT);

        for (DBObject userDAO : userCursor) {

            final ObjectId userIdAsObjectId = (ObjectId) userDAO.get(UserDAO.ID);

            // Get blahs authored by user
            final DBCursor blahCursor = Utilities.findInDB(3, "finding a user's blah records", blahsCollection, new BasicDBObject(BlahDAO.AUTHOR_ID, userIdAsObjectId.toString()), null);

            blahCursor.addOption(Bytes.QUERYOPTION_SLAVEOK);
            blahCursor.addOption(Bytes.QUERYOPTION_NOTIMEOUT);

            Double totalBlahStrength = 0.0;
            Double totalBlahControversialStrength = 0.0;
            for (DBObject blahDAO : blahCursor) {
                final Double blahStrength = (Double) blahDAO.get(BlahDAOConstants.BLAH_STRENGTH);
                if (blahStrength != null) {
                    totalBlahStrength += blahStrength;
                }
                final Double upVotes = Utilities.getValueAsDouble(blahDAO.get(BlahDAOConstants.PROMOTED_COUNT), 0D);
                final Double downVotes = Utilities.getValueAsDouble(blahDAO.get(BlahDAOConstants.DEMOTED_COUNT), 0D);
                if (upVotes != null && downVotes != null) {
                    if (upVotes != 0 && downVotes != 0) {
                        final double ratio = (downVotes > upVotes) ? (upVotes / downVotes) : (downVotes / upVotes);
                        if (ratio > 0.80) {
                            totalBlahControversialStrength += 1D;
                        }
                    }
                }
            }
            userStrengths.add(totalBlahStrength);
            userControversyStrengths.add(totalBlahControversialStrength * totalBlahStrength); // skew it by blah's strength
            userIdToTotalBlahStrength.put(userIdAsObjectId, totalBlahStrength);
            userIdToTotalBlahControversialStrength.put(userIdAsObjectId, totalBlahControversialStrength);
        }

        if (userStrengths.size() == 0) {
            return; // nothing to compute
        }

        // Compute stats over all users
        final double strengthMax = userStrengths.size() == 0 ? 1.0d : Descriptive.max(userStrengths);
        final double strengthMin = userStrengths.size() == 0 ? 0.0d : Descriptive.min(userStrengths);
        final double strengthRange = strengthMax - strengthMin;

        final double controversyStrengthMax = userControversyStrengths.size() == 0 ? 1.0d : Descriptive.max(userControversyStrengths);
        final double controversyStrengthMin = userControversyStrengths.size() == 0 ? 0.0d : Descriptive.min(userControversyStrengths);
        final double controversyStrengthRange = controversyStrengthMax - controversyStrengthMin;


//        final int userCount = userIdToTotalBlahStrength.size();
        for (Map.Entry<ObjectId, Double> strengthEntry : userIdToTotalBlahStrength.entrySet()) {

            final ObjectId userIdAsObjectId = strengthEntry.getKey();
            final Double totalUserStrength = strengthEntry.getValue();
            final Double totalControversyUserStrength = userIdToTotalBlahControversialStrength.get(userIdAsObjectId);

            Double strength;
            try {
                strength = (strengthRange == 0d) ? 0d : Double.valueOf(Utilities.toThreeDecimalDouble((totalUserStrength - strengthMin) / strengthRange));
            } catch (Exception e) {
                strength = 0d;
            }
            Double controversyStrength;
            try {
                controversyStrength = (controversyStrengthMax == 0d) ? 0d : Double.valueOf(Utilities.toThreeDecimalDouble((totalControversyUserStrength - controversyStrengthMin) / controversyStrengthRange));
            } catch (Exception e) {
                controversyStrength = 0d;
            }

            if (doUpdate) {
                final BasicDBObject criteria = new BasicDBObject(UserDAO.ID, userIdAsObjectId);
                final DBObject updates = new BasicDBObject(UserDAOConstants.USER_STRENGTH, strength);
                updates.put(UserDAOConstants.USER_CONTROVERSY_STRENGTH, controversyStrength);
                final DBObject setter = new BasicDBObject("$set", updates);
                usersCollection.update(criteria, setter);
            }
        }
    }

    // TODO Looks like this is no longer necessary! Check and take out if so.
    private void updateTrackersForOverallStats() throws Exception {
        if (!doUpdate) {
            return;
        }
        final DBCursor userTrackerCursor = userTrackerCollection.find().sort(new BasicDBObject(UserTrackerDAO.ID, 1));

        userTrackerCursor.addOption(Bytes.QUERYOPTION_SLAVEOK);
        userTrackerCursor.addOption(Bytes.QUERYOPTION_NOTIMEOUT);

        for (DBObject userTracker : userTrackerCursor) {
            final String trackerId = (String) userTracker.get(UserTrackerDAO.ID);
            final DBObject updates = new BasicDBObject();
            for (Map.Entry<String, Data> entry : overallFieldNameToAllUsersDailiesMap.entrySet()) {
                final String fieldName = entry.getKey();
                final String aveFieldName = "O" + UT_STATS_AVERAGE_FIELD_NAME_PREFIX + fieldName;
                updates.put(aveFieldName, entry.getValue().average);
                final String sdFieldName = "O" + UT_STATS_STANDARD_DEVIATION_FIELD_NAME_PREFIX + fieldName;
                updates.put(sdFieldName, entry.getValue().sd);
//                System.out.println(fieldName+"="+entry.getValue());
            }
            final DBObject setter = new BasicDBObject("$set", updates);
            userTrackerCollection.update(new BasicDBObject(UserTrackerDAO.ID, trackerId), setter);
        }
    }

    // TODO should these be done incrementally to avoid the overhead of the hash table?
    private void computeOverallStatistics() {
        for (Map.Entry<String, Data> entry : overallFieldNameToAllUsersDailiesMap.entrySet()) {
            final String fieldName = entry.getKey();
            final Data dailies = entry.getValue();
            dailies.average = Utilities.toThreeDecimalDouble(Descriptive.mean(dailies.dailies));
            dailies.sd = Utilities.toThreeDecimalDouble(
                    Descriptive.standardDeviation(
                            Descriptive.variance(dailies.dailies.size(), Descriptive.sum(dailies.dailies), Descriptive.sumOfSquares(dailies.dailies))));
        }
    }

    /**
     * The "last valid day" in a month of dailies is today.
     *
     * @param trackerId The user tracker id
     * @return int Returns the last valid day of the month for this tracker.
     *         First day of month is 0.
     */
    private int getLastValidDayOfMonthZeroOrigin(String trackerId) {

        try {
            // Get tracker's month and year
            final String date = trackerId.substring(trackerId.length() - 4);
            final String year = date.substring(0, 2);
            final int yr = Integer.parseInt(year) + 2000;
            final String month = date.substring(2, 4);
            final int mo = Integer.parseInt(month) - 1;

            // Is it this month and year?
            final Calendar cal = GregorianCalendar.getInstance();
            final int thisMonth = cal.get(Calendar.MONTH);
            final int thisYear = cal.get(Calendar.YEAR);
            final boolean isNow = (mo == thisMonth) && (yr == thisYear);

            if (isNow) {
                return cal.get(Calendar.DATE) - 1;
            }
            return cal.getActualMaximum(Calendar.DAY_OF_MONTH) - 1;
        } catch (NumberFormatException e) {
            e.printStackTrace();
            return 1000;
        }
    }

    private void updateTracker(DBObject updates, String trackerId) {
        final DBObject setter = new BasicDBObject("$set", updates);
        userTrackerCollection.update(new BasicDBObject(UserTrackerDAO.ID, trackerId), setter);
    }

    private DBObject computeUserStatistics() {
        final DBObject updates = new BasicDBObject();
        computeAverages(updates);
        computeStandardDeviations(updates);
        return updates;
    }

    private void computeAverages(DBObject updates) {
        if (fieldNameToDailiesMap != null) {
            for (Map.Entry<String, Data> entry : fieldNameToDailiesMap.entrySet()) {
                final String fieldName = entry.getKey();
                final Data data = entry.getValue();
                data.average = Utilities.toThreeDecimalDouble(Descriptive.mean(data.dailies));
                final Double formattedDouble = Utilities.toThreeDecimalDouble(data.average);

                final String aveFieldName = UT_STATS_AVERAGE_FIELD_NAME_PREFIX + fieldName;
                updates.put(aveFieldName, formattedDouble);
            }
        }
    }

    private void computeStandardDeviations(DBObject updates) {
        if (fieldNameToDailiesMap != null) {
            for (Map.Entry<String, Data> entry : fieldNameToDailiesMap.entrySet()) {
                final String fieldName = entry.getKey();
                final Data data = entry.getValue();
                data.sd = Utilities.toThreeDecimalDouble(Descriptive.standardDeviation(Descriptive.variance(data.dailies.size(), Descriptive.sum(data.dailies), Descriptive.sumOfSquares(data.dailies))));

                final String sdFieldName = UT_STATS_STANDARD_DEVIATION_FIELD_NAME_PREFIX + fieldName;
                Double val;
                try {
                    val = Double.valueOf(Utilities.toThreeDecimalDouble(Utilities.toThreeDecimalDouble(Descriptive.standardDeviation(Descriptive.variance(data.dailies.size(), Descriptive.sum(data.dailies), Descriptive.sumOfSquares(data.dailies))))));
                } catch (Exception e) {
                    val = 0d;
                }
                updates.put(sdFieldName, val);
            }
        }

    }

    private void registerData(List<Object> dailies) {
        if (fieldNameToDailiesMap != null) {
            for (Map.Entry<String, Data> entry : fieldNameToDailiesMap.entrySet()) {
                final String fieldName = entry.getKey();
                final Data data = entry.getValue();
                for (Object daily : dailies) {
                    final DBObject d = (DBObject) daily;
                    data.dailies.add(Utilities.getValueAsDouble(d.get(fieldName), 0D));
                }
            }
        }
    }

    class Data {
        DoubleArrayList dailies = new DoubleArrayList();
        Double average = 0.0;
        Double sd = 0.0;

        public String toString() {
            return "[size=" + dailies.size() + " ave=" + average + " sd=" + sd + "]";
        }
    }

    //    static final NumberFormat nf = NumberFormat.getNumberInstance();
//
//    static {
//        nf.setMaximumFractionDigits(2);
//    }

}
