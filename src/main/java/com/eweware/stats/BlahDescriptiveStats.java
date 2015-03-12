package com.eweware.stats;

import com.mongodb.*;
import com.eweware.service.base.store.dao.BaseDAOConstants;
import com.eweware.service.base.store.dao.BlahDAOConstants;
import com.eweware.stats.help.Utilities;

import java.text.NumberFormat;
import java.util.*;

/**
 * <p>Calculates blah strength using Wilson lower-bound estimator.</p>
 * <p>This algorithm is linear on the number of blahs.</p>
 * <p>We boost the strength of a blah that is less than a day old whose lower bound is less than or
 * equal to 0.67. The boosted strength is proportional to how recently the blah was created,
 * in half hour segments.</p>
 *
 * @author rk@post.harvard.edu
 *         Date: 9/30/12 Time: 6:08 PM
 */
public class BlahDescriptiveStats {

    public final static int HALF_HOUR_IN_MILLIS = 1000 * 60 * 30;
    public final static  int NUMBER_OF_HALF_HOUR_BUCKETS = 48; // two-days' worth

    private class E {
        double raw, strength;
        E(double raw, double strength) {
            this.raw = raw;
            this.strength = strength;
        }
    }

    private static double[] buckets;


    private static final double[] getBuckets() {
        if (buckets != null) {
            return buckets;
        }
        buckets = new double[NUMBER_OF_HALF_HOUR_BUCKETS];
        final double increment = 1d / NUMBER_OF_HALF_HOUR_BUCKETS;
        double strength = 0L;
        for (int i = NUMBER_OF_HALF_HOUR_BUCKETS - 1; i >= 0; i--) {
            buckets[i] = strength;
            strength += increment;
        }
        return buckets;
    }

    /**
     * <p>Returns the recent strength solely based on the created time in millis.</p>
     * <p>The meaning of "recency" may vary in different implementations.</p>
     * @param createdTimeInMillis
     * @return Returns the strength or null if the created time is not "recent".
     */
    public static Double getRecentStrength(long createdTimeInMillis) {
        final int index = Math.round(((System.currentTimeMillis() - createdTimeInMillis) / HALF_HOUR_IN_MILLIS)) - 1;// 48 segments (each 1/2 hr long)
        if (index < NUMBER_OF_HALF_HOUR_BUCKETS && index >= 0) {
            return getBuckets()[index];
        }
        return null;
    }

    public long execute() throws Exception {

        final Date cutoffDate = Utilities.getDateBeforeInDays(Main.recentStrengthCutoffInDays);
        final Date halflifeDate = Utilities.getDateBeforeInDays(30);
        final Date dropDeadDate = Utilities.getDateBeforeInDays(60);

        final DBObject fieldsToReturn = new BasicDBObject(BaseDAOConstants.ID, 1);
        fieldsToReturn.put(BlahDAOConstants.PROMOTED_COUNT, 1);
        fieldsToReturn.put(BlahDAOConstants.DEMOTED_COUNT, 1);
        fieldsToReturn.put(BlahDAOConstants.PREDICTION_RESULT_CORRECT_COUNT, 1);
        fieldsToReturn.put(BlahDAOConstants.PREDICTION_RESULT_INCORRECT_COUNT, 1);
        fieldsToReturn.put(BlahDAOConstants.PREDICTION_RESULT_UNCLEAR_COUNT, 1);
        fieldsToReturn.put(BlahDAOConstants.PREDICTION_USER_AGREE_COUNT, 1);
        fieldsToReturn.put(BlahDAOConstants.PREDICTION_USER_DISAGREE_COUNT, 1);
        fieldsToReturn.put(BlahDAOConstants.PREDICTION_USER_UNCLEAR_COUNT, 1);
        fieldsToReturn.put(BlahDAOConstants.POLL_OPTION_VOTES, 1);
        fieldsToReturn.put(BlahDAOConstants.OPENS, 1);
        fieldsToReturn.put(BlahDAOConstants.COMMENTS, 1);
        fieldsToReturn.put(BlahDAOConstants.IMAGE_IDS, 1);
        fieldsToReturn.put(BlahDAOConstants.BADGE_IDS, 1);
        fieldsToReturn.put(BlahDAOConstants.BLAH_STRENGTH, 1);
        fieldsToReturn.put(BaseDAOConstants.CREATED, 1);

        final DBCollection blahCollection = DBCollections.getInstance().getBlahsCol();
        final HashMap<String, E> entries = Main._verbose ? new HashMap<String, E>() : null;

        ArrayList orList = new ArrayList();
        orList.add(new BasicDBObject("S", new BasicDBObject("$gte", 0)));
        orList.add(new BasicDBObject("S", new BasicDBObject("$exists", false)));

        final BasicDBObject blahQuery = new BasicDBObject("$or", orList);
        final DBCursor blahs = Utilities.findInDB(3, "finding blah records", blahCollection, blahQuery, fieldsToReturn);
        blahs.addOption(Bytes.QUERYOPTION_SLAVEOK);
        blahs.addOption(Bytes.QUERYOPTION_NOTIMEOUT);
        long blahCount = 0;
        for (DBObject blah : blahs) {

            blahCount++;

            final Object blahDBObjectId = blah.get(BaseDAOConstants.ID);
            final Long promotions = Utilities.getValueAsLong(blah.get(BlahDAOConstants.PROMOTED_COUNT), 0L);
            final Long demotions = Utilities.getValueAsLong(blah.get(BlahDAOConstants.DEMOTED_COUNT), 0L);
            final Long predictionCorrect = Utilities.getValueAsLong(blah.get(BlahDAOConstants.PREDICTION_RESULT_CORRECT_COUNT), 0L);
            final Long predictionIncorrect = Utilities.getValueAsLong(blah.get(BlahDAOConstants.PREDICTION_RESULT_INCORRECT_COUNT), 0L);
            final Long predictionUnresolvable = Utilities.getValueAsLong(blah.get(BlahDAOConstants.PREDICTION_RESULT_UNCLEAR_COUNT), 0L);
            final Long predictionAgree = Utilities.getValueAsLong(blah.get(BlahDAOConstants.PREDICTION_USER_AGREE_COUNT), 0L);
            final Long predictionDisagree = Utilities.getValueAsLong(blah.get(BlahDAOConstants.PREDICTION_USER_DISAGREE_COUNT), 0L);
            final Long predictionUnclear = Utilities.getValueAsLong(blah.get(BlahDAOConstants.PREDICTION_USER_UNCLEAR_COUNT), 0L);
            final List<String> imageIds = (List<String>) blah.get(BlahDAOConstants.IMAGE_IDS);
            final double imageWeight = (imageIds != null && imageIds.size() > 0)?1.0D:0.0D;
            final List<String> badgeIds = (List<String>) blah.get(BlahDAOConstants.BADGE_IDS);
            final double badgeWeight = (badgeIds != null && badgeIds.size() > 0)?0.3D:0.0D;
            final List<Long> pollVotes = (List<Long>) blah.get(BlahDAOConstants.POLL_OPTION_VOTES);
            Long pollVoteTotal = 0L;
            if (pollVotes != null) {
                for (Long v : pollVotes) {
                    if (v != null) pollVoteTotal += v;
                }
            }
            final Long opens = Utilities.getValueAsLong(blah.get(BlahDAOConstants.OPENS), 0L);
            final Long comments = Utilities.getValueAsLong(blah.get(BlahDAOConstants.COMMENTS), 0L);
            final Date created = (Date) blah.get(BaseDAOConstants.CREATED);

            // Prediction weights:
            //   If many of people agree and disagree (minus those who think it's unclear) makes it popular
            final double predictionPopularity = predictionAgree + predictionDisagree - (0.67d * predictionUnclear);
            //   The correct count minus those who think it's incorrect and minus a proportion of those who think it's unresolvable
            final double predictionCorrectness = predictionCorrect - predictionIncorrect - (0.67d * predictionUnresolvable);

            final double raw = comments + (promotions * 1.2) + (opens * 0.3) + predictionPopularity + predictionCorrectness + (pollVoteTotal * 0.3) + imageWeight + badgeWeight;
            double strength = getWilsonLowerBound(raw, (demotions * .2));

            if (created.after(cutoffDate)) {
                if (strength < 0.67D) {
                    final Double recentStrength = getRecentStrength(created.getTime());
                    if (recentStrength != null) {
                        strength = recentStrength;
                    }
                }
            } else if (created.before(halflifeDate)) {
                strength /= 4;
                if (created.before(dropDeadDate))
                    strength = 0D;   // TODO:  should be the date since last activity

            }


            final BasicDBObject setters = new BasicDBObject();

            setters.put(BlahDAOConstants.BLAH_STRENGTH, strength);

            final DBObject criteria = new BasicDBObject(BaseDAOConstants.ID, blahDBObjectId);
            final DBObject updateObj = new BasicDBObject("$set", setters);
            if (Main._verbose) {
                if (raw != 0D && strength != 0D) {
                    entries.put(blahDBObjectId.toString(), new E(raw, strength));
                }
            }
            blahCollection.update(criteria, updateObj);
        }

        if (Main._verbose) {
            final TreeMap<String, E> map = new TreeMap<String, E>(new Comp(entries));
            map.putAll(entries);
            for (Map.Entry<String, E> entry : map.entrySet()) {
                System.out.println(entry.getKey() + ": raw=" + entry.getValue().raw + " strength=" + entry.getValue().strength);
            }
        }

        return blahCount;
    }


    // Comparator for entries hashtable
    private class Comp implements Comparator<String> {

        private final Map<String, E> map;

        Comp(Map<String, E> map) {
            this.map = map;
        }
        @Override
        public int compare(String blahId1, String blahId2) {
            final Double a = map.get(blahId1).raw;
            final Double b = map.get(blahId2).raw;
            return (!a.equals(b)) ? a.compareTo(b) : blahId1.compareTo(blahId2);
        }
    }


    private final double _z = 1.64485; // 95% confidence
    private final double _zSquared = _z * _z;

    private double getWilsonLowerBound(Double positive, Double negative) {
        final double rating = positive + negative;
        if (rating == 0) {
            return 0;
        }
        final double phi = positive / rating;
        return (phi + _zSquared / (2 * rating) - _z * Math.sqrt((phi * (1 - phi) + _zSquared / (4 * rating)) / rating)) / (1 + _zSquared / rating);
    }

//    static final NumberFormat nf = NumberFormat.getNumberInstance();
//
//    static {
//        nf.setMaximumFractionDigits(2);
//    }
}
