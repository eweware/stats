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

import java.util.*;


public class UserClusterer {

    private final DBCollection _userBlahInfoCol;

    private class UserBlahMap {

        private final HashMap<String, HashMap<String, Integer>>  _blahMap = new HashMap<String, HashMap<String, Integer>>();

        public void setUserBlahCount(String userId, String blahId, int vote, int openCount)  {
            HashMap<String, Integer> curMap = _blahMap.get(userId);
            if (curMap == null) {
                curMap = new HashMap<String, int>();
                _blahMap.put(userId, curMap);
            }

            int newVal = 0;
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
            int openCount = (Integer)obj.get(UserBlahInfoDAOConstants.OPENS);
            int vote = (Integer)obj.get(UserBlahInfoDAOConstants.PROMOTION);

            setUserBlahCount(userId, blahId, vote, openCount);
        }


        public double getUserDistance(String userOneId, String userTwoId) {
            double distance = 50;

            HashMap<String, Integer> userOneMap = _blahMap.get(userOneId);
            HashMap<String, Integer> userTwoMap = _blahMap.get(userTwoId);
            if ((userOneMap != null)  && (userTwoMap != null)) {
                if (userTwoMap.size() < userOneMap.size()) {
                    HashMap<String, Integer> tempMap = userOneMap;
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
                    Integer curRec = userTwoMap.get(curBlahId);
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



    public UserClusterer() throws DBException {
        _userBlahInfoCol = DBCollections.getInstance().getUserBlahInfoCol();

    }

    public long execute() throws DBException, SystemErrorException, InterruptedException {
        final BasicDBObject fieldsToReturn = makeBlahInfoFieldsToReturn();
        BasicDBObject queryObj = new BasicDBObject(UserBlahInfoDAO.VIEWS, new BasicDBObject("$gt", 0)).append(UserBlahInfoDAO.OPENS, new BasicDBObject("$gte", 0));
        final DBCursor cursor = Utilities.findInDB(3, "finding all user blah records", _userBlahInfoCol, queryObj, fieldsToReturn);
        for (DBObject group : cursor) {
            getUserDistance()
        }
        Utilities.printit(true, "Added total " + addedInboxItemCount + " inbox objects");
        return addedInboxItemCount;
    }

    private BasicDBObject makeBlahInfoFieldsToReturn() {
        final BasicDBObject fieldsToReturn = new BasicDBObject(UserBlahInfoDAOConstants.USER_ID, 1);
        fieldsToReturn.put(UserBlahInfoDAOConstants.BLAH_ID, 1);
        fieldsToReturn.put(UserBlahInfoDAOConstants.OPENS, 1);
        fieldsToReturn.put(UserBlahInfoDAOConstants.PROMOTION, 1);
        return fieldsToReturn;
    }

}
