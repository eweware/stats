package com.eweware.stats;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.eweware.DBException;
import com.eweware.service.base.CommonUtilities;
import com.eweware.service.base.store.dao.BaseDAOConstants;
import com.eweware.service.base.store.dao.InboxBlahDAOConstants;
import org.bson.types.ObjectId;

import java.util.Date;

/**
 * @author rk@post.harvard.edu
 *         Date: 7/12/13 Time: 5:44 PM
 */
public class UpdateRecents {

    private static final int HALF_HOUR_IN_MILLIS = (1000 * 60 * 30);
    private static long lastUpdateInMillis = 0L;

    public long execute() throws DBException {

        long count = 0;
        if ((System.currentTimeMillis() - lastUpdateInMillis) > HALF_HOUR_IN_MILLIS) {
            final DBCollections dbcols = DBCollections.getInstance();
            final DB inboxdb = dbcols.getDB("inboxdb");
            final BasicDBObject criteria = new BasicDBObject();
            for (DBObject group : dbcols.getGroupsCol().find()) {
                final DBCollection inboxcol = inboxdb.getCollection(CommonUtilities.makeRecentsInboxCollectionName(group.get(BaseDAOConstants.ID).toString()));
                for (DBObject item : inboxcol.find()) {
                    final Date created = (Date) item.get(BaseDAOConstants.CREATED);
                    final Double currentStrength = BlahDescriptiveStats.getRecentStrength(created.getTime());
                    if (currentStrength != null) {
                        final Double lastStrength = (Double) item.get(InboxBlahDAOConstants.BLAH_STRENGTH);
                        if ((lastStrength > 0) && (currentStrength != lastStrength)) {
                            criteria.put(BaseDAOConstants.ID, item.get(BaseDAOConstants.ID));
                            inboxcol.update(criteria, new BasicDBObject("$set", new BasicDBObject(InboxBlahDAOConstants.BLAH_STRENGTH, currentStrength)));
                        }
                        count++;
                    }
                }
            }
            lastUpdateInMillis = System.currentTimeMillis();
        }
        return count;
    }
}
