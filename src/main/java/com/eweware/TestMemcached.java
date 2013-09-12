package main.java.com.eweware;

/**
 * @author rk@post.harvard.edu
 *         Date: 10/6/12 Time: 4:38 PM
 */
public class TestMemcached {

//    public static void main(String[] a) throws SystemErrorException {
//        final BlahCache cache = new BlahCache(new BlahCacheConfiguration("127.0.0.1", "11211"));
//        try {
//            final MemcachedClient client = cache.getClient();
//
//            final String groupId = "group-id";
//            final Integer inbox = 0;
//
//            for (int i = 0; i < 2; i++) {
//                final String newItemKey = cache.makeInboxItemKey((String) "itemid-"+i);
//                final Map<String, Object> itemData = new HashMap<String, Object>();
//                itemData.put("x", newItemKey + "-xdata-"+i);
//                itemData.put("y", newItemKey + "-ydata-"+i);
//
//                setViaCas(cache, client, groupId, inbox, newItemKey, itemData);
//
//                final String newStateKey = cache.makeInboxStateKey(groupId, inbox);
//                final InboxState state = (InboxState) client.get(newStateKey);
//                final List<String> itemIds = state.getItemIds();
//                final Map<String, Object> bulk = client.getBulk(itemIds);
//                final Collection<Object> values = bulk.values();
//                final ArrayList<Map<String, Object>> items = new ArrayList<Map<String, Object>>(values.size());
//                for (Object value: values) items.add((Map<String, Object>) value);
//
//                System.out.println("INBOX "+inbox+" topInbox="+state.getTopInbox()+"\n    itemIds="+state.getItemIds()+"\n    items="+items);
//            }
////            final Collection<Object> values = bulk.values();
////            if (values.size() > 0) {
////                final Object next = values.iterator().next();
////                System.out.println("=> "+ next);
////            }
////            final Object result = client.get(newStateKey);
////            System.out.println("KEY="+newStateKey+" VALUE=" + result);
//
//        } catch (Exception e) {
//            e.printStackTrace();
//            System.exit(-1);
//        } finally {
//            if (cache != null) {
//                cache.shutdown();
//            }
//        }
//        System.exit(0);
//
//    }
//
//    private static void setViaCas(BlahCache cache, MemcachedClient client, String groupId, Integer inbox, final String newItemKey, Map<String, Object> itemData) throws Exception {
//
//        client.set(newItemKey, 0, itemData);
//
//        final CASMutation<InboxState> casMutation = new CASMutation<InboxState>() {
//            @Override
//            public InboxState getNewValue(InboxState currentState) {
//                if (currentState == null) {
//                    throw new RuntimeException("NOT EXPECTED TO HAVE currentState");
//                }
//                final List<String> ids = new ArrayList<String>(currentState.getItemIds());
//                ids.add(newItemKey);
//                return new InboxState(currentState.getTopInbox(), ids);
//            }
//        };
//        // new state (in case none is set: the expectation
//        final List<String> itemIds = Arrays.asList(new String[]{newItemKey});
//        final InboxState newState = new InboxState(0, itemIds);
//        final String newStateKey = cache.makeInboxStateKey(groupId, inbox);
//
////        final SerializingTranscoder tc = new SerializingTranscoder();
//        final CASMutator<InboxState> mutator = new CASMutator(client, client.getTranscoder()); // (Transcoder<InboxState>) client.getTranscoder());
//        final InboxState dbg = mutator.cas(newStateKey, newState, 0, casMutation);
////        System.out.println("DEBUG MUTATOR; STATE RETURNED: " + dbg);
////        final Object result = client.get(newStateKey);
////        System.out.println("DEBUG MUTATOR; ACTUAL VALUE: " + result);
//    }
//
//    private static void cacheTest() {
//        try {
//
//            MemcachedClient cache = new MemcachedClient(AddrUtil.getAddresses("127.0.0.1:11211"));
//
//            String key = "KEY";
//
////            cache.set("A", 0, "a");
////            cache.set("B", 0, "b");
////            cache.set("C", 0, "c");
//
//            // read the object from memory
//            final Map<String,Object> bulk = cache.getBulk("A", "B", "C");
//            System.out.println("Get :" + bulk);
//
//            // set a new object
////            Object value = new BasicDBObject("_id", "1119 Brighton Street");
////            System.out.println("VALUE="+value);
////            cache.set("KEY", 0, value );
////            cache.delete("KEY");
//
//
//        } catch (IOException ex) {
//            Logger.getLogger(TestMemcached.class.getName()).log(Level.SEVERE, null, ex);
//            System.exit(0);
//        }
//    }
}
