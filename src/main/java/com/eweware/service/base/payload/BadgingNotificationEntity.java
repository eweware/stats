package com.eweware.service.base.payload;

import com.eweware.service.base.store.dao.BadgeDAOConstants;

/**
 * @author rk@post.harvard.edu
 *         Date: 3/25/13 Time: 2:02 PM
 */
public interface BadgingNotificationEntity {
    /**
     * The transaction id.
     */
    public static final String TRANSACTION_ID_FIELDNAME = "T";

    /**
     * The id of the granted badge.
     */
    public static final String BADGE_ID_FIELDNAME = "I";

    /**
     * The badge type
     */
    public static final String BADGE_TYPE_ID_FIELDNAME = BadgeDAOConstants.BADGE_TYPE;

    /**
     * The name of this authority (domain name).
     */
    public static final String AUTHORITY_FIELDNAME = "A";

    /**
     * The display name for the badge. In this case,
     * it is the domain name of the grantee's email address.
     */
    public static final String BADGE_NAME_FIELDNAME = "N";

    /**
     * The state of the grant
     */
    public static final String STATE_FIELDNAME = "S";

    /**
     * An optional icon that can be displayed to
     * represent this badge. Not used for now.
     */
    public static final String ICON_URL_FIELDNAME = "K";

    /**
     * The expiration date of this badge.
     */
    public static final String EXPIRATION_DATETIME_FIELDNAME = "E";

    /**
     * State values.
     */
    public static final String STATE_GRANTED = "g";   /* badge granted */
    public static final String STATE_REFUSED = "r";   /* badge refused */
    public static final String STATE_CANCELLED = "c"; /* user cancelled */
    public static final String STATE_SERVER_ERROR = "e"; /* badge not granted due to a server error */

    /**
     * Error codes.
     * These are the acceptable error codes in response to this notification entity when POSTed to a server.
     */
    public static final Integer ERROR_CODE_TRANSACTION_UNKNOWN = 1;
    public static final Integer ERROR_CODE_TRANSACTION_SERVER_ERROR = 2; /** requesting app had an error handling the notification */
    public static final Integer ERROR_CODE_TRANSACTION_MISSING_BADGES = 3; /** authority granted badges, but no badges were send to requesting app */
}
