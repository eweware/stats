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
import org.bson.types.ObjectId;

import java.util.*;


public class UserClusterer {

    private final DBCollection _userBlahInfoCol;

    private class BlahScoreMap extends HashMap<String, Long> {

    }

    private class UserBlahMap extends HashMap<String, BlahScoreMap> {
        public void setUserBlahCount(String userId, String blahId, long vote, long openCount)  {
            BlahScoreMap curMap = this.get(userId);
            if (curMap == null) {
                curMap = new BlahScoreMap();
                this.put(userId, curMap);
            }

            long newVal = 0;
            if (vote < 0)
                newVal = -10;
            else {
                if (openCount > 0) {
                    newVal += 1;
                    if (openCount > 1)
                        newVal += 1;
                }

                if (vote > 0)
                    newVal += 5;
            }
            curMap.put(blahId, newVal);
        }

        public void addUserRecord(DBObject obj) {
            String userId = (String)obj.get(UserBlahInfoDAOConstants.USER_ID);
            String blahId = (String)obj.get(UserBlahInfoDAOConstants.BLAH_ID);
            Long openCountRef = (Long)obj.get(UserBlahInfoDAOConstants.OPENS);
            Long voteRef = (Long)obj.get(UserBlahInfoDAOConstants.PROMOTION);

            long openCount = 0;
            long vote = 0;
            if (openCountRef != null)
                openCount = openCountRef.longValue();
            if (voteRef != null)
                vote = voteRef.longValue();
            setUserBlahCount(userId, blahId, vote, openCount);
        }


        public double getUserDistance(String userOneId, String userTwoId) {
            double distance = 50;

            BlahScoreMap userOneMap = this.get(userOneId);
            BlahScoreMap userTwoMap = this.get(userTwoId);
            if ((userOneMap != null)  && (userTwoMap != null)) {
                if (userTwoMap.size() < userOneMap.size()) {
                    BlahScoreMap tempMap = userOneMap;
                    userOneMap = userTwoMap;
                    userTwoMap = tempMap;
                }
                Set set = userOneMap.entrySet();
                // Get an iterator
                Iterator i = set.iterator();
                // Display elements
                while(i.hasNext()) {
                    Map.Entry me = (Map.Entry)i.next();
                    String curBlahId = (String)me.getKey();
                    Long curRec = userTwoMap.get(curBlahId);
                    if (curRec != null) {
                        int curScoreTwo = curRec.intValue();
                        int curScoreOne = (Integer)me.getValue();
                        if (curScoreOne > 0) {
                            if (curScoreTwo > 0) {
                                // both opened and maybe voted!
                                distance -= (int)((curScoreOne + curScoreTwo) / 2);
                            } else if (curScoreTwo == 0) {
                                // user one opened but user two ignored - not too significant
                            } else {
                                // user one liked but user two didn't
                                distance += (curScoreOne - curScoreTwo );
                            }
                        } else if (curScoreOne == 0) {
                            // user one didn't vote
                            if (curScoreTwo == 0) {
                                // they both gave it a miss...  significant?
                            } else {
                                // user two opened something user one didn't
                            }
                        } else {
                            // user one did not like this
                            if (curScoreTwo < 0) {
                                // both didn't like
                                distance += (int)((curScoreOne + curScoreTwo) / 2);
                            } else if (curScoreTwo == 0) {
                                // one didn't like, two didn't care
                            } else {
                                // one didn't like, two liked - oops!
                                distance += (curScoreTwo - curScoreOne );
                            }
                        }
                    }
                }
            }

            if (distance < 0)
                distance = 0;

            return distance;
        }

    }

    class GroupUserMap extends HashMap<String, UserBlahMap> {

        public void addUserRecord(String groupId, DBObject obj) {
            UserBlahMap curMap = this.get(groupId);
            if (curMap == null) {
                curMap = new UserBlahMap();
                this.put(groupId, curMap);
            }

            curMap.addUserRecord(obj);
        }
    }



    public UserClusterer() throws DBException {
        _userBlahInfoCol = DBCollections.getInstance().getUserBlahInfoCol();

    }

    public long execute() throws DBException, SystemErrorException, InterruptedException {
        long userCount = 0;
        GroupUserMap curMap = new GroupUserMap();

        EnsureGroupTypesAdded();
        final DBCursor groupCursor = Utilities.findInDB(3, "finding all channels", DBCollections.getInstance().getGroupsCol(), null, null);
        final BasicDBObject fieldsToReturn = makeBlahInfoFieldsToReturn();

        for (DBObject curGroup : groupCursor) {
            String curGroupId = curGroup.get(BaseDAOConstants.ID).toString();
            BasicDBObject queryObj = new BasicDBObject(UserBlahInfoDAOConstants.VIEWS, new BasicDBObject("$gt", 0)).append(UserBlahInfoDAOConstants.OPENS, new BasicDBObject("$gte", 0)).append(UserBlahInfoDAOConstants.ORIGINAL_GROUP_ID, curGroupId);
            final DBCursor cursor = Utilities.findInDB(3, "finding all user blah records", _userBlahInfoCol, queryObj, fieldsToReturn);
            for (DBObject userInfo : cursor) {
                curMap.addUserRecord(curGroupId, userInfo);
                userCount++;
            }
        }

        // now for each channel we have the opinion of each user for each blah

        Utilities.printit(true, "Computed grid for " + userCount + " users");
        return userCount;
    }

    private int EnsureGroupTypesAdded() throws DBException, InterruptedException {
        DBCollection    blahCollection = DBCollections.getInstance().getBlahsCol();
        List<String>  blahIdList = _userBlahInfoCol.distinct(UserBlahInfoDAOConstants.BLAH_ID, new BasicDBObject(UserBlahInfoDAOConstants.ORIGINAL_GROUP_ID, new BasicDBObject("$exists", false)));

        int numUpdated = 0;
        for (String curBlahStr : blahIdList) {
            ObjectId    blahId = new ObjectId(curBlahStr);
            DBObject    theBlah = blahCollection.findOne(new BasicDBObject(BaseDAOConstants.ID, blahId), new BasicDBObject(BlahDAOConstants.GROUP_ID, 1));
            String groupId = theBlah.get(BlahDAOConstants.GROUP_ID).toString();

            final BasicDBObject setters = new BasicDBObject();
            setters.put(UserBlahInfoDAOConstants.ORIGINAL_GROUP_ID, groupId);

            final DBObject criteria = new BasicDBObject(UserBlahInfoDAOConstants.BLAH_ID, curBlahStr);
            final DBObject updateObj = new BasicDBObject("$set", setters);
            WriteResult result = _userBlahInfoCol.update(criteria, updateObj, false, true);
            numUpdated += result.getN();
        }
        return numUpdated;
    }

    private BasicDBObject makeBlahInfoFieldsToReturn() {
        final BasicDBObject fieldsToReturn = new BasicDBObject(UserBlahInfoDAOConstants.USER_ID, 1);
        fieldsToReturn.put(UserBlahInfoDAOConstants.BLAH_ID, 1);
        fieldsToReturn.put(UserBlahInfoDAOConstants.OPENS, 1);
        fieldsToReturn.put(UserBlahInfoDAOConstants.PROMOTION, 1);
        return fieldsToReturn;
    }

}
