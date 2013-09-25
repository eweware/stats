package com.eweware.stats.help;


import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;

import java.io.IOException;

/**
 * @author rk@post.harvard.edu
 *         Date: 6/16/12 Time: 11:57 AM
 */
public class JSONUtilities {

    public static String getAsJsonString(Object obj) throws Exception {
        return new ObjectMapper().writeValueAsString(obj);
    }

    public static Object getAsBean(String jsonString, Class beanClass) throws IOException {
        ObjectMapper m = new ObjectMapper();
        // Serialize inherited fields:
//        m.setVisibilityChecker(m.getVisibilityChecker().withFieldVisibility(JsonAutoDetect.Visibility.ANY));
        return m.readValue(jsonString, beanClass);
    }

    public static Object getAsBean(String jsonString, TypeReference beanClass) throws IOException {
        ObjectMapper m = new ObjectMapper();
        return m.readValue(jsonString, beanClass);
    }

}
