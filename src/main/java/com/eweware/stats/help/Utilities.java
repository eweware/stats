package main.java.com.eweware.stats.help;

import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import main.java.com.eweware.ApplicationException;
import main.java.com.eweware.DBException;
import main.java.com.eweware.service.base.error.SystemErrorException;
import main.java.com.eweware.service.base.store.dao.BaseDAOConstants;
import main.java.com.eweware.service.base.store.dao.DemographicsObjectDAOConstants;
import main.java.com.eweware.service.base.store.dao.UserProfileDAOConstants;
import main.java.com.eweware.service.base.store.dao.schema.SchemaSpec;
import main.java.com.eweware.service.base.store.dao.schema.UserProfileSchema;
import main.java.com.eweware.service.base.store.dao.schema.type.SchemaDataType;
import main.java.com.eweware.stats.DBCollections;
import main.java.com.eweware.stats.Main;
import main.java.com.eweware.stats.ObjectInfo;
import org.bson.types.ObjectId;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author rk@post.harvard.edu
 *         Date: 9/26/12 Time: 7:10 PM
 */
public final class Utilities {

    private static final Logger logger = Logger.getLogger(Utilities.class.getName());

    public static final String UNSPECIFIED_AGE_BUCKET_FIELDNAME = "-1";

    public static final boolean allowWrites = true;

    public static int rangeRandom(Random rand, final int min, final int max) {
        return min + rand.nextInt(max - min + 1);  // +1 for including the max
    }
//
//    public static Integer safeGetInteger(String integerAsString, Integer defaultValue) {
//        try {
//            return new Integer(integerAsString);
//        } catch (NumberFormatException e) {
//            return defaultValue;
//        }
//    }

    /**
     * from izomorphius
     *
     * @param min        The minimum range number
     * @param max        The maximum range number
     * @param weights    Array containing distributed weight values. The sum of values must be 1.0
     * @param probabiity Array containing distributed probability values. The array length must be same with weights and the sum of values must be 1.0
     * @return Random number
     * @throws Exception Probably should create own exception, but I use default Exception for simplicity
     */
    public static int weightedRangeRandom(Random rand, final int min, final int max, final float[] weights, final float[] probabiity) {
        // some validations
        if (weights.length != probabiity.length) {
            throw new RuntimeException("Length of weight & chance must be equal");
        }

        int len = weights.length;

        float sumWeight = 0, sumChance = 0;
        for (int i = 0; i < len; ++i) {
            sumWeight += weights[i];
            sumChance += probabiity[i];
        }
        if (sumWeight != 1.0 || sumChance != 1.0) {
            throw new RuntimeException("Sum of weight/chance must be 1.0");
        }

        // find the random number
        int tMin = min, tMax;
        int rangeLen = max - min + 1;

        double n = Math.random();
        float c = 0;
        for (int i = 0; i < len; ++i) {
            if (i != (len - 1)) {
                tMax = tMin + Math.round(weights[i] * rangeLen) - 1;
            } else {
                tMax = max;
            }

            c += probabiity[i];
            if (n < c) {
                return rangeRandom(rand, tMin, tMax);
            }
            tMin = tMax + 1;
        }

        throw new RuntimeException("algorithm error");
    }

    public static void computeDemographics(UserProfileSchema userProfileSchema, DBObject userProfile, Map<String, Map<String, Long>> fieldNameToFieldValueToCountMap, Long increment) {
        Map<String, Long> demoFieldValueToCountMap;
        final Map<Object, Boolean> userIdHasEmptyDemoFieldMap = new HashMap<Object, Boolean>();
        for (Map.Entry<String, SchemaSpec> entry : userProfileSchema.getFieldNameToSpecMap().entrySet()) {

            final String demographicFieldName = entry.getKey(); // e.g. gender
            final SchemaSpec spec = entry.getValue();
            final SchemaDataType dataType = spec.getDataType();

            if (dataType == SchemaDataType.ILS || dataType == SchemaDataType.ILN) { // ignore others (like date of birth)
                demoFieldValueToCountMap = fieldNameToFieldValueToCountMap.get(demographicFieldName);
                if (demoFieldValueToCountMap == null) {
                    demoFieldValueToCountMap = new HashMap<String, Long>();
                    fieldNameToFieldValueToCountMap.put(demographicFieldName, demoFieldValueToCountMap);
                }
                Object demographicFieldValue; // = (userProfile == null) ? spec.getDefaultValue() : userProfile.get(demographicFieldName);
                if (userProfile == null) {
                    demographicFieldValue = spec.getDefaultValue();
                } else {
                    final Object value = userProfile.get(demographicFieldName);
                    if (value == null) {
                        demographicFieldValue = spec.getDefaultValue();
                    } else {
                        demographicFieldValue = value;
                    }
                }
                if (demographicFieldValue == null || !(demographicFieldValue instanceof String)) {
                    if (userProfile != null) {
                        if (userIdHasEmptyDemoFieldMap.get(userProfile.get(BaseDAOConstants.ID)) == Boolean.TRUE) {  // show warning only once
                            continue;
                        }
                        userIdHasEmptyDemoFieldMap.put(userProfile.get(BaseDAOConstants.ID), Boolean.TRUE);
                    }
                    Utilities.printitNoReturn(new Date() + ": WARNING: demographics field value=" + demographicFieldValue + " is not a String for fieldName=" + demographicFieldName);
                    if (userProfile == null) {
                        Utilities.printit();
                    } else {
                        Utilities.printit(" userId=" + userProfile.get(BaseDAOConstants.ID));
                    }
                    continue;
                }
                incrementValueCount(demoFieldValueToCountMap, demographicFieldValue, increment);
            } else if (demographicFieldName.equals(UserProfileDAOConstants.USER_PROFILE_DATE_OF_BIRTH)) {  // Assuming data type!
                demoFieldValueToCountMap = fieldNameToFieldValueToCountMap.get(demographicFieldName);
                if (demoFieldValueToCountMap == null) {
                    demoFieldValueToCountMap = new HashMap<String, Long>();
                    fieldNameToFieldValueToCountMap.put(demographicFieldName, demoFieldValueToCountMap);
                }
                Object demographicFieldValue;
                if (userProfile == null) {
                    demographicFieldValue = null;  // unspecified
                } else {
                    demographicFieldValue = userProfile.get(demographicFieldName);
                    if ((demographicFieldValue != null) && !(demographicFieldValue instanceof Date)) {
                        if (userProfile != null) {
                            if (userIdHasEmptyDemoFieldMap.get(userProfile.get(BaseDAOConstants.ID)) == Boolean.TRUE) {  // show warning only once
                                continue;
                            }
                            userIdHasEmptyDemoFieldMap.put(userProfile.get(BaseDAOConstants.ID), Boolean.TRUE);
                        }
                        Utilities.printitNoReturn(new Date() + ": WARNING: demographics field value=" + demographicFieldValue + " is not a Date for fieldName=" + demographicFieldName);
                        if (userProfile == null) {
                            Utilities.printit();
                        } else {
                            Utilities.printit(" userId=" + userProfile.get(BaseDAOConstants.ID));
                        }
                        continue;
                    }
                }
                incrementValueCountForAge(demoFieldValueToCountMap, demographicFieldValue, increment);
            }
        }
    }

    /**
     * Aggregates the demo data in newFieldNameToFieldValueToCountMap into
     * the data in newFieldNameToFieldValueToCountMap
     *
     * @param lastFieldNameToFieldValueToCountMap
     *
     * @param newFieldNameToFieldValueToCountMap
     *
     */
    public static void aggregateDemoFieldValueCounts(Map<String, Map<String, Long>> lastFieldNameToFieldValueToCountMap,
                                                     Map<String, Map<String, Long>> newFieldNameToFieldValueToCountMap) {
        for (Map.Entry<String, Map<String, Long>> entry : newFieldNameToFieldValueToCountMap.entrySet()) {
            final String fieldName = entry.getKey();
            final Map<String, Long> newFieldValueToCountMap = entry.getValue();
            final Map<String, Long> lastFieldValueToCountMap = lastFieldNameToFieldValueToCountMap.get(fieldName);
            if (lastFieldValueToCountMap == null) {
                lastFieldNameToFieldValueToCountMap.put(fieldName, newFieldValueToCountMap);
            } else {
                for (Map.Entry<String, Long> newFieldValueToCountEntry : newFieldValueToCountMap.entrySet()) {
                    final String fieldValue = newFieldValueToCountEntry.getKey();
                    final Long count = newFieldValueToCountEntry.getValue();
                    final Long oldValue = lastFieldValueToCountMap.get(fieldValue);
                    lastFieldValueToCountMap.put(fieldValue, (oldValue == null) ? count : count + oldValue);
                }
            }
        }
    }

    /**
     * Increments the key's value of the specified map by a given amount.
     *
     * @param keyToCountMap The map
     * @param key           The key
     * @param increment     The amount to increment
     */
    public static final void incrementValueCount(Map<String, Long> keyToCountMap, Object key, Long increment) {
        final Long count = keyToCountMap.get(key);
        if (key instanceof String) {
            keyToCountMap.put((String) key, (count == null) ? increment : count + increment);
        } else {
            throw new RuntimeException("Can't handle user profile field value '" + key + "'");
        }
    }

    public static final void incrementValueCountForAge(Map<String, Long> keyToCountMap, Object dob, Long increment) {
        if (dob != null && !(dob instanceof Date)) {
            throw new RuntimeException("Can't handle user profile field value '" + dob + "'");
        }
        final String bucket = (dob == null) ? UNSPECIFIED_AGE_BUCKET_FIELDNAME : Utilities.getAgeBucket(Utilities.getAgeInYears((Date) dob), UNSPECIFIED_AGE_BUCKET_FIELDNAME);
        final Long count = keyToCountMap.get(bucket);
        keyToCountMap.put(bucket, (count == null) ? increment : count + increment);
    }

    /**
     * Aggregates the data for the specified object id and map.
     *
     * @param id The object's id (e.g., a blah id, comment id, etc.)
     * @param actionToObjectIdToObjectInfoMap
     *           Maps the action to a map between an object id and its information
     * @throws Exception
     */
    public static void writeAggregate(String id, Map<String, Map<String, ObjectInfo>> actionToObjectIdToObjectInfoMap) throws Exception {
        Map<String, Map<String, Map<String, Long>>> writeActionFieldNameToFieldValueToCountAggregateMap = new HashMap<String, Map<String, Map<String, Long>>>();
        for (Map.Entry<String, Map<String, ObjectInfo>> actionToObjectIdToFieldNameToFieldValueToCountEntry : actionToObjectIdToObjectInfoMap.entrySet()) {
            final String action = actionToObjectIdToFieldNameToFieldValueToCountEntry.getKey();
            Map<String, Map<String, Long>> writeFieldNameToFieldValueToCountAggregateMap = writeActionFieldNameToFieldValueToCountAggregateMap.get(action);
            if (writeFieldNameToFieldValueToCountAggregateMap == null) {
                writeFieldNameToFieldValueToCountAggregateMap = new HashMap<String, Map<String, Long>>();
                writeActionFieldNameToFieldValueToCountAggregateMap.put(action, writeFieldNameToFieldValueToCountAggregateMap);
            }
            final Map<String, ObjectInfo> objectIdToFieldNameToFieldValueToCountMap = actionToObjectIdToFieldNameToFieldValueToCountEntry.getValue();
            for (Map.Entry<String, ObjectInfo> ObjectIdToFieldNameToFieldValueToCountEntry : objectIdToFieldNameToFieldValueToCountMap.entrySet()) {
                final ObjectInfo objectInfo = ObjectIdToFieldNameToFieldValueToCountEntry.getValue();
                for (Map.Entry<String, Map<String, Long>> fieldNameToFieldValueToCountEntry : objectInfo.fieldNameToFieldValueToCountMap.entrySet()) {
                    final String fieldName = fieldNameToFieldValueToCountEntry.getKey();
                    Map<String, Long> writeFieldValueToCountMap = writeFieldNameToFieldValueToCountAggregateMap.get(fieldName);
                    if (writeFieldNameToFieldValueToCountAggregateMap.get(fieldName) == null) {
                        writeFieldValueToCountMap = new HashMap<String, Long>();
                        writeFieldNameToFieldValueToCountAggregateMap.put(fieldName, writeFieldValueToCountMap);
                    }
                    final Map<String, Long> fieldValueToCountMap = fieldNameToFieldValueToCountEntry.getValue();
                    for (Map.Entry<String, Long> fieldValueToCountEntry : fieldValueToCountMap.entrySet()) {
                        final String fieldValue = fieldValueToCountEntry.getKey();
                        final Long count = fieldValueToCountEntry.getValue();
                        final Long writeCount = writeFieldValueToCountMap.get(fieldValue);
                        writeFieldValueToCountMap.put(fieldValue, (writeCount == null) ? count : count + writeCount);
                    }
                }
            }
        }
        final DBCollection col = DBCollections.getInstance().getCollection("trackerdb", "demographics");
        final BasicDBObject stats = new BasicDBObject("_id", id);
        stats.put("updated", new Date());
        stats.putAll(writeActionFieldNameToFieldValueToCountAggregateMap);
        if (allowWrites) {
            col.save(stats);
        }
    }

    /**
     * For each object in the specified collection, it writes the demo stats to it
     * for each action field (e.g., open, vote). If a demo action datum is empty, it unsets
     * the stats for that action.
     *
     * @param collection The collection (e.g., blahs, comments)
     * @param actionToObjectIdToObjectInfoMap
     *                   The action map with the demo data.
     * @return The count of processed objects
     */
    public static long writeAggregateToObjects(DBCollection collection, Map<String, Map<String, ObjectInfo>> actionToObjectIdToObjectInfoMap) {
        long count = 0;
        for (DBObject object : collection.find()) {
            ObjectId objectId = (ObjectId) object.get(BaseDAOConstants.ID);
            final String objectIdAsString = objectId.toString();
            final BasicDBObject demoData = new BasicDBObject();
            final DBObject demoObject = new BasicDBObject(DemographicsObjectDAOConstants.DEMOGRAPHICS_RECORD, demoData);
            for (Map.Entry<String, Map<String, ObjectInfo>> actionToObjectIdToFieldNameToFieldValueToCountMap : actionToObjectIdToObjectInfoMap.entrySet()) {
                final String label = actionToObjectIdToFieldNameToFieldValueToCountMap.getKey();
                final Map<String, ObjectInfo> objectIdToFieldNameToFieldValueToCountMap = actionToObjectIdToFieldNameToFieldValueToCountMap.getValue();
                final ObjectInfo objectInfo = objectIdToFieldNameToFieldValueToCountMap.get(objectIdAsString);
                if (objectInfo != null && !objectInfo.isEmpty()) {
                    demoData.put(label, objectInfo.fieldNameToFieldValueToCountMap);
                }
            }
            final DBObject update = new BasicDBObject(demoData.isEmpty() ? "$unset" : "$set", demoObject);
            if (allowWrites) {
                collection.update(new BasicDBObject(BaseDAOConstants.ID, objectId), update);
            }
            count++;
        }
        return count;
    }

    /**
     * @param time         Time
     * @param earliestTime Earliest time
     * @return boolean Returns true if the specified time is later than the given earliest time
     */
    public static boolean getCreatedDaysAgo(Date time, Date earliestTime) {
        return (time != null) && time.after(earliestTime);
    }

    public static void computeDemographicsForAction(String objectId,
                                                    UserProfileSchema userProfileSchema,
                                                    DBObject userProfile,
                                                    Map<String, ObjectInfo> objectIdToDemographicsCount,
                                                    Long increment) {
        ObjectInfo objectInfo = new ObjectInfo();

        computeDemographics(userProfileSchema, userProfile, objectInfo.fieldNameToFieldValueToCountMap, increment);

        ObjectInfo lastObjectInfo = objectIdToDemographicsCount.get(objectId);
        if (lastObjectInfo == null) {
            lastObjectInfo = new ObjectInfo();
            objectIdToDemographicsCount.put(objectId, lastObjectInfo);
            lastObjectInfo.fieldNameToFieldValueToCountMap.putAll(objectInfo.fieldNameToFieldValueToCountMap);
        } else {
            aggregateDemoFieldValueCounts(
                    lastObjectInfo.fieldNameToFieldValueToCountMap,
                    objectInfo.fieldNameToFieldValueToCountMap);
        }
    }

    public static void computeDemographicsForAllActions(String objectId, UserProfileSchema userProfileSchema, DBObject userProfile,
                                                        Map<String, Map<String, ObjectInfo>> actionToObjectIdToObjectInfoMap,
                                                        Long vote, Long views, Long opens, Long comments) {
        for (Map.Entry<String, Map<String, ObjectInfo>> entry : actionToObjectIdToObjectInfoMap.entrySet()) {
            final String label = entry.getKey();
            final Map<String, ObjectInfo> objectIdToBlahInfoMap = entry.getValue();
            // TODO this presumes to know what's inside DemographicsObjectDAOConstants's demo field names array!
            if (label.equals(DemographicsObjectDAOConstants.UP_VOTE_COUNT)) {
                computeDemographicsForAction(objectId, userProfileSchema, userProfile, objectIdToBlahInfoMap, vote > 0L ? 1L : 0L);
            } else if (label.equals(DemographicsObjectDAOConstants.DOWN_VOTE_COUNT)) {
                computeDemographicsForAction(objectId, userProfileSchema, userProfile, objectIdToBlahInfoMap, vote < 0L ? 1L : 0L);
            } else if (label.equals(DemographicsObjectDAOConstants.VIEW_COUNT)) {
                computeDemographicsForAction(objectId, userProfileSchema, userProfile, objectIdToBlahInfoMap, views);
            } else if (label.equals(DemographicsObjectDAOConstants.OPEN_COUNT)) {
                computeDemographicsForAction(objectId, userProfileSchema, userProfile, objectIdToBlahInfoMap, opens);
            } else if (label.equals(DemographicsObjectDAOConstants.COMMENT_COUNT)) {
                computeDemographicsForAction(objectId, userProfileSchema, userProfile, objectIdToBlahInfoMap, comments);
            }
        }
    }

    public static String getUserTrackerDate(String trackerId) {
        final int length = trackerId.length();
        return trackerId.substring(length - 4);
    }


    public static String getBlahOrCommentTrackerDate(String trackerId) {
        final int length = trackerId.length();
        return trackerId.substring(length - 6);
    }

    public static void fillMapWithPossibleActions(Map<String, Map<String, ObjectInfo>> actionToBlahIdToBlahInfoMap) {
        for (String action : DemographicsObjectDAOConstants.DEMOGRAPHICS_RECORD_FIELD_NAMES) {
            actionToBlahIdToBlahInfoMap.put(action, new HashMap<String, ObjectInfo>());
        }
    }

    public static Date getDateBeforeInDays(int daysBeforeToday) {
        final Calendar cal = GregorianCalendar.getInstance();
        cal.add(Calendar.DATE, -1 * daysBeforeToday);
//        cal.set(Calendar.MILLISECOND, 999);
//        cal.set(Calendar.SECOND, 59);
//        cal.set(Calendar.MINUTE, 59);
//        cal.set(Calendar.HOUR, 23);
        return new Date(cal.getTimeInMillis());
    }

    public static void printit() {
        if (Main._verbose) {
            System.out.println();
        }
    }

    public static void printit(Object thing) {
        printit(false, thing);
    }

    public static void printitNoReturn(Object thing) {
        if (Main._verbose) {
            System.out.print(thing);
        }
    }

    public static void printit(boolean force, Object thing) {
        if (thing != null && (force || Main._verbose)) {
            System.out.println(thing);
        }
    }

    public static final double toThreeDecimalDouble(double number) {
        return Math.round(number * 1000) / 1000.0;
    }

    public static String safeGetString(String string, String defaultValue) {
        if (string == null) {
            return defaultValue;
        }
        return string;
    }

    public static boolean safeGetBoolean(String string, boolean defaultValue) {
        if (string == null) {
            return defaultValue;
        }
        return string.trim().toLowerCase().equals("true");
    }


    public static long getCountFromDB(int attempts, String message, DBCollection collection, DBObject query) throws InterruptedException, DBException {
        try {
            return (query == null) ? collection.count() : collection.count(query);
        } catch (Throwable e) {
            final StringBuilder b = new StringBuilder("Error while trying to " + message);
            --attempts;
            if (attempts > 0) {
                b.append(" Will sleep for 5 seconds and attempt " + attempts + " more times.");
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e1) {
                    logger.log(Level.SEVERE, "Interrupted " + message, e);
                    throw e1;
                }
            } else {
                b.append(" Gave up!");
            }
            logger.log(Level.WARNING, b.toString(), e);
            if (attempts <= 0) {
                throw new DBException(b.toString(), e);
            }
            return getCountFromDB(attempts, message, collection, query); // try again
        }
    }

    public static DBCursor findInDB(int attempts, String message, DBCollection collection, DBObject query, DBObject fieldsToReturn) throws InterruptedException, DBException {
        try {
            if (query != null && fieldsToReturn != null) {
                return collection.find(query, fieldsToReturn);
            } else if (query != null) {
                return collection.find(query);
            } else if (fieldsToReturn != null) {
                return collection.find(null, fieldsToReturn);
            } else {
                return collection.find();
            }
        } catch (Throwable e) {
            final StringBuilder b = new StringBuilder("Error while trying to " + message);
            --attempts;
            if (attempts > 0) {
                b.append(" Will sleep for 5 seconds and attempt " + attempts + " more times.");
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e1) {
                    logger.log(Level.SEVERE, "Interrupted " + message, e);
                    throw e1;
                }
            } else {
                b.append(" Gave up!");
            }
            logger.log(Level.WARNING, b.toString(), e);
            if (attempts <= 0) {
                throw new DBException(b.toString(), e);
            }
            return findInDB(attempts, message, collection, query, fieldsToReturn); // try again
        }
    }

    public static DBObject findOneInDB(int attempts, String message, DBCollection collection, DBObject query, DBObject fieldsToReturn) throws InterruptedException, DBException {
        try {
            if (query != null && fieldsToReturn != null) {
                return collection.findOne(query, fieldsToReturn);
            } else if (query != null) {
                return collection.findOne(query);
            } else if (fieldsToReturn != null) {
                return collection.findOne(null, fieldsToReturn);
            } else {
                return collection.findOne();
            }
        } catch (Throwable e) {
            final StringBuilder b = new StringBuilder("Error while trying to " + message);
            --attempts;
            if (attempts > 0) {
                b.append(" Will sleep for 5 seconds and attempt " + attempts + " more times.");
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e1) {
                    logger.log(Level.SEVERE, "Interrupted " + message, e);
                    throw e1;
                }
            } else {
                b.append(" Gave up!");
            }
            logger.log(Level.WARNING, b.toString(), e);
            if (attempts <= 0) {
                throw new DBException(b.toString(), e);
            }
            return findOneInDB(attempts, message, collection, query, fieldsToReturn); // try again
        }
    }


//    public static final Integer getValueAsInteger(Object val, Integer defaultValue) {
//        if (val == null) return 0;
//        if (val instanceof Integer) {
//            return (Integer) val;
//        }
//        if (val instanceof Double) {
//            return new Integer(((Double) val).intValue());
//        }
//        if (val instanceof Long) {
//            return new Integer(((Long) val).intValue());
//        }
//        if (val instanceof String) {
//            return Integer.parseInt((String) val);
//        }
//        if (val instanceof Float) {
//            return new Integer(((Float) val).intValue());
//        }
//        return defaultValue;
//    }


    public static int getAgeInYears(Date dob) {
        Calendar dateOfBirth = new GregorianCalendar();
        dateOfBirth.setTimeInMillis(dob.getTime());
        Calendar today = new GregorianCalendar();
        int age = today.get(Calendar.YEAR) - dateOfBirth.get(Calendar.YEAR);
        if ((dateOfBirth.get(Calendar.MONTH) > today.get(Calendar.MONTH))
                || (dateOfBirth.get(Calendar.MONTH) == today.get(Calendar.MONTH) && dateOfBirth.get(Calendar.DAY_OF_MONTH) > today.get(Calendar.DAY_OF_MONTH))) {
            age--;
        }
        return age;
    }

    public static final int[] ageBuckets = new int[]{
//            75, 65, 55, 45, 35, 25, 18, 12, 0
            65, 55, 45, 35, 25, 18, 13
    };

    public static String getAgeBucket(int age, String defaultBucket) {
        int count = ageBuckets.length;
        for (int i = 0; i < count; i++) {
            if (age >= ageBuckets[i]) {
                return String.valueOf(i);
            }
        }
        return defaultBucket;
    }

    public static final Long getValueAsLong(Object val) throws SystemErrorException {
        if (val == null) return 0L;
        if (val instanceof Long) {
            return (Long) val;
        }
        try {
            if (val instanceof Double) {
                return new Long(Math.round((Double) val));
            }
            if (val instanceof Integer) {
                return new Long(((Integer) val).intValue());
            }
            if (val instanceof String) {
                return Long.parseLong((String) val);
            }
        } catch (Exception e) {
            // fall through
        }
        throw new SystemErrorException("Can't handle value=" + val);
    }

    public static final Long getValueAsLong(Object val, Long defaultValue) throws SystemErrorException {
        if (val instanceof Long) {
            return (Long) val;
        }
        try {
            if (val instanceof Double) {
                return new Long(Math.round((Double) val));
            }
            if (val instanceof Integer) {
                return new Long(((Integer) val).intValue());
            }
            if (val instanceof String) {
                return Long.parseLong((String) val);
            }
        } catch (Exception e) {
            // fall through
        }
        return defaultValue;
    }

    public static final Integer getValueAsInteger(Object val, Integer defaultValue) throws SystemErrorException {
        if (val == null) {
            return defaultValue;
        }
        if (val instanceof Integer) {
            return (Integer) val;
        }
        try {
            if (val instanceof Long) {
                return ((Long) val).intValue();
            }
            if (val instanceof Double) {
                return new Long(Math.round((Double) val)).intValue();
            }
            if (val instanceof String) {
                return Integer.parseInt((String) val);
            }
        } catch (Exception e) {
            // fall through
        }
        return defaultValue;
    }

    public static Double getValueAsDouble(Object val, double defaultValue) {
        if (val == null) {
            return defaultValue;
        }
        if (val instanceof Double) {
            return (Double) val;
        }
        try {
            if (val instanceof Integer) {
                return ((double) ((Integer) val).intValue());
            }
            if (val instanceof Long) {
                return ((double) ((Long) val).longValue());
            }
            if (val instanceof String) {
                return Double.parseDouble((String) val);
            }
        } catch (Exception e) {
            // fall through
        }
        return defaultValue;
    }
}