package main.java.com.eweware.stats.help;



import main.java.com.eweware.service.base.store.dao.GroupDAO;
import main.java.com.eweware.service.base.store.dao.type.BlahTypeCategoryType;

import java.util.HashMap;
import java.util.Map;

/**
 * @author rk@post.harvard.edu
 *         Date: 9/27/12 Time: 11:30 AM
 */
public final class Constants {

    public static class GroupDescription {
        public String name;
        public String descriptor;

        public GroupDescription(String name, String descriptor) {
            this.name = name;
            this.descriptor = descriptor;
        }
    }

    public static final GroupDescription[] workplaceGroupDescriptions = new GroupDescription[]{
            new GroupDescription("Apple, Inc.", GroupDAO.GroupDescriptor.VISIBILITY_OTHER.getCode()),
            new GroupDescription("Microsoft Corporation", GroupDAO.GroupDescriptor.VISIBILITY_OTHER.getCode()),
            new GroupDescription("Google, Inc.", GroupDAO.GroupDescriptor.VISIBILITY_OTHER.getCode()),
            new GroupDescription("Netflix, Inc.", GroupDAO.GroupDescriptor.VISIBILITY_OTHER.getCode()),
            new GroupDescription("Facebook, Inc.", GroupDAO.GroupDescriptor.VISIBILITY_OTHER.getCode()),
    };

    public static final GroupDescription[] locationGroupDescriptions = new GroupDescription[]{
            new GroupDescription("Silicon Valley", GroupDAO.GroupDescriptor.VISIBILITY_OPEN.getCode()),
            new GroupDescription("Japan", GroupDAO.GroupDescriptor.VISIBILITY_OTHER.getCode())
    };

    public static final GroupDescription[] schoolGroupDescriptions = new GroupDescription[]{
            new GroupDescription("New College, Florida", GroupDAO.GroupDescriptor.VISIBILITY_OTHER.getCode()),
            new GroupDescription("University of Southern California", GroupDAO.GroupDescriptor.VISIBILITY_OTHER.getCode())
    };

    public static final GroupDescription[] socialGroupDescriptions = new GroupDescription[]{
            new GroupDescription("Blahgua Network", GroupDAO.GroupDescriptor.VISIBILITY_OPEN.getCode())

    };

    public static final Map<String, GroupDescription[]> groupTypeNameToGroupDescriptionMap = new HashMap<String, GroupDescription[]>();

    static {
        Constants.groupTypeNameToGroupDescriptionMap.put("Workplace", Constants.workplaceGroupDescriptions);
        Constants.groupTypeNameToGroupDescriptionMap.put("Location", Constants.locationGroupDescriptions);
        Constants.groupTypeNameToGroupDescriptionMap.put("School", Constants.schoolGroupDescriptions);
        Constants.groupTypeNameToGroupDescriptionMap.put("Social Networks", Constants.socialGroupDescriptions);
    }

    public static final String[] usernamePrefixes = new String[]{
            "arch", "archie", "aaron", "alice", "amelia", "ann", "anton", "arthur", "allen",
            "butch", "barby", "bev", "beverley", "beatrice", "benice", "bebe", "bad",
            "carl", "charlie", "charles", "connie", "caren", "coco", "candice", "curt",
            "dave", "david", "don", "donald", "daniel", "dan", "danielle", "deborah", "debbie",
            "ed", "edward", "ellie", "echo", "edgar",
            "frank", "fred", "flinstone", "fink",
            "george", "geoff", "gilbert",
            "harold", "heath", "holmes",
            "ignacio", "ian",
            "john", "jon", "jill", "jude", "judy", "judith", "jan", "janice", "jim",
            "lana", "lowell", "leon", "lowell",
            "mike", "michael", "morris", "mown", "moma", "muriel",
            "nathan", "nolan", "norris",
            "octavius",
            "peter", "pedro", "patrick", "pan",
            "roo", "ruben", "roy", "rachel", "robin", "radar",
            "steve", "sugar", "spivak",
            "ted", "todd", "tim", "tom",
    };

    public static class BlahTypeDescriptor {
        public final String name;
        public final Integer category;

        public BlahTypeDescriptor(String name, Integer category) {
            this.name = name;
            this.category = category;
        }
    }

    public static final BlahTypeDescriptor[] BLAH_TYPE_DESCRIPTORS = new BlahTypeDescriptor[7];

    public static final String SAYS = "says";
    public static final String WHISPERS = "whispers";
    public static final String POLLS = "polls";
    public static final String PREDICTS = "predicts";
    public static final String WISHES = "wishes";
    public static final String LEAKS = "leaks";
    public static final String PAID = "paid";

    static {
        Constants.BLAH_TYPE_DESCRIPTORS[0] = (new BlahTypeDescriptor(SAYS, BlahTypeCategoryType.DEFAULT.getCategoryId()));
        Constants.BLAH_TYPE_DESCRIPTORS[1] = (new BlahTypeDescriptor(WHISPERS, BlahTypeCategoryType.DEFAULT.getCategoryId()));
        Constants.BLAH_TYPE_DESCRIPTORS[2] = (new BlahTypeDescriptor(POLLS, BlahTypeCategoryType.POLL.getCategoryId()));
        Constants.BLAH_TYPE_DESCRIPTORS[3] = (new BlahTypeDescriptor(PREDICTS, BlahTypeCategoryType.DEFAULT.getCategoryId()));
        Constants.BLAH_TYPE_DESCRIPTORS[4] = (new BlahTypeDescriptor(WISHES, BlahTypeCategoryType.DEFAULT.getCategoryId()));
        Constants.BLAH_TYPE_DESCRIPTORS[5] = (new BlahTypeDescriptor(LEAKS, BlahTypeCategoryType.DEFAULT.getCategoryId()));
        Constants.BLAH_TYPE_DESCRIPTORS[6] = (new BlahTypeDescriptor(PAID, BlahTypeCategoryType.DEFAULT.getCategoryId()));
    }

    public static final String[] neutralCommentPhrases = new String[]{
            "This should be investigated: ", "The part that gets me is: ", "This is something new! ", "I was out hunting sheep when I heard this about ",
            "Maybe, maybe not! ", "What I say is: You can pick your friends and you can pick your nose, but you can't pick your friend's nose. ",
    };
    public static final String[] positiveCommentPhrases = new String[]{
            "I do really agree that ", "It must be true: ", "I've been waiting to hear that ", "It makes me glad that ", "Astounded that ", "I always suspected that ",
    };
    public static final String[] negativeCommentPhrases = new String[]{
            "I can't believe that ", "Not true! Why would ", "Something smells fishy here. Why would ", "Sure! And fairies grow in trees... How can ", "Really? -- Not! This thing about ",
    };

}
