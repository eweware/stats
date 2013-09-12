package main.java.com.eweware.stats;

import java.util.HashMap;
import java.util.Map;

/**
 * @author rk@post.harvard.edu
 *         Date: 9/28/12 Time: 11:21 PM
 */
public class ObjectInfo {

    public Map<String, Map<String, Long>> fieldNameToFieldValueToCountMap = new HashMap<String, Map<String, Long>>();
    public boolean isEmpty() {
        return fieldNameToFieldValueToCountMap.isEmpty();
    }

}
