package main.java.com.eweware.stats;

import main.java.com.eweware.service.base.cache.BlahCache;
import main.java.com.eweware.stats.help.Utilities;

import java.lang.management.OperatingSystemMXBean;
import java.text.NumberFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.management.ManagementFactory.getOperatingSystemMXBean;

/**
 * @author rk@post.harvard.edu
 *         Date: 10/11/12 Time: 9:22 PM
 */
public class Main extends Thread {

    private static final Logger logger = Logger.getLogger(Main.class.getName());


    private static final int INBOX_REST_IN_MINUTES = 2;
    private static final int INBOX_REST_IN_MILLIS = 1000 * 60 * INBOX_REST_IN_MINUTES;
    private static final int STATS_REST_IN_MINUTES = 2;
    private static final int STATS_REST_IN_MILLIS = 1000 * 60 * STATS_REST_IN_MINUTES;

    public static boolean _verbose;

    /**
     * <p>If true, we calculate blah strength and build inboxes; else
     * we compute demographics.</p>
     */
    private static boolean _buildInboxes;

    // Range in days before today where a strength is considered "recent"
    public static int recentStrengthCutoffInDays = 1;

    private static final List<String> PROD_DB_HOSTNAMES = Arrays.asList(new String[]{"rs1-1.mongo.blahgua.com", "rs1-2.mongo.blahgua.com", "rs1-3.mongo.blahgua.com"});
    private static final List<String> QA_DB_HOSTNAMES = Arrays.asList(new String[]{"qa.db.blahgua.com"});
    private static final List<String> DEV_DB_HOSTNAMES = Arrays.asList(new String[]{"localhost"});

    private static final Integer DEFAULT_DB_PORT = 21191;

    private static List<String> dbHostnames;
    private static final Integer _dbPort = DEFAULT_DB_PORT;

    public static void main(String[] args) {
        parseArgs(args);
        printConfig();
        new Main().start();
    }

    private static boolean parseArgs(String[] args) {
        if (args.length > 1) {
            final String job = args[1];
            _buildInboxes = job.toLowerCase().equals("inboxes");
            if (args.length > 2) {
                final String verbose = args[2];
                _verbose = verbose.toLowerCase().equals("true");
            }
            final String mode = args[0].toLowerCase();
            if (mode.equals("prod")) {
                dbHostnames = PROD_DB_HOSTNAMES;
                return true;
            } else if (mode.equals("qa")) {
                dbHostnames = QA_DB_HOSTNAMES;
                return true;
            } else if (mode.equals("dev")) {
                dbHostnames = DEV_DB_HOSTNAMES;
                return true;
            }
        }
        usageAndExit();
        return false;

    }

    private static void printConfig() {
        System.out.println("***** START CONFIGURATION *****");
        System.out.println("OPERATION: " + (_buildInboxes ? "Inboxes" : "Demographics"));
        System.out.println("DB HOSTNAME(S): " + dbHostnames);
        System.out.println("DB PORT: " + _dbPort);
        System.out.println("VERBOSE: " + _verbose);
        System.out.println("****** END CONFIGURATION ******");
    }

    private static void usageAndExit() {
        System.err.println("\nUSAGE: java -jar stats-1.0.0-jar-with-dependencies.jar <mode> <operation>[<verbose>]");
        System.err.println("WHERE, <mode> := {prod|qa|dev}\n       <job> := {inboxes|demo} (to create inboxes or demographics)\n       <verbose> := {true|false}");
        System.exit(-1);
    }

    public static List<String> getDbHostnames() {
        return dbHostnames;
    }

    public static Integer get_dbPort() {
        return _dbPort;
    }

    public static boolean isVerbose() {
        return _verbose;
    }

    public void run() {
        try {
            addShutdownHook();

            Utilities.printit(true, new Date() + ": Calculating stats on DB host(s) " + dbHostnames);

//            readConfigFile();

            while (true) {

                Utilities.printit(true, getResourceUsageReport());

                try {
                    doit();
                } catch (Throwable e) {
                    logger.log(Level.SEVERE, "Failed pass due to unrecoverable error.", e);
                    logger.warning("Continuing...");
                }

                Utilities.printit(true, new Date() + ": Pausing " + (_buildInboxes ? "Inbox" : "Statistics") + " job for " + (_buildInboxes ? INBOX_REST_IN_MINUTES : STATS_REST_IN_MINUTES) + " minutes...");
                Thread.sleep(_buildInboxes ? INBOX_REST_IN_MILLIS : STATS_REST_IN_MILLIS);
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Aborted application.", e);
            System.exit(-1);
        }
        System.exit(0);
    }

    private void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                System.out.println("*** Interrupted: releasing resources ***");
                final BlahCache cache = BlahCache.getInstance();
                if (cache != null) {
                    cache.shutdown();
                    System.out.println("*** Shutdown: BlahCache ***");
                }
            }
        });
    }

    private long passCount = 1;

    private void doit() throws Exception {

        long lastTime = System.currentTimeMillis();
        long startJob = lastTime;
        long blahCount = 0L;
        long ms = 0L;

        if (_buildInboxes) {
            blahCount = new BlahDescriptiveStats().execute();
            ms = System.currentTimeMillis() - lastTime;
            Utilities.printit(true, new Date() + ": BlahDescriptiveStats took " + (ms / 1000) + " seconds (" + (ms / ((blahCount == 0L) ? 1L : blahCount)) + " ms/blah)");

            lastTime = System.currentTimeMillis();
            new Inboxer().execute();
            Utilities.printit(true, new Date() + ": InboxBuilder took " + ((System.currentTimeMillis() - lastTime) / 1000) + " seconds");
        } else {
            lastTime = System.currentTimeMillis();
            new UserDescriptiveStats().execute();
            Utilities.printit(true, new Date() + ": UserDescriptiveStats took " + ((System.currentTimeMillis() - lastTime) / 1000) + " seconds");

            lastTime = System.currentTimeMillis();
            blahCount = new BlahDemographics().execute();
            Utilities.printit(true, new Date() + ": BlahDemographics took " + ((System.currentTimeMillis() - lastTime) / 1000) + " seconds");

            lastTime = System.currentTimeMillis();
            final long commentCount = new CommentDemographics().execute();
            ms = System.currentTimeMillis() - lastTime;
            Utilities.printit(true, new Date() + ": CommentDemographics took " + (ms / 1000) + " seconds (" + (ms / ((commentCount == 0L) ? 1L : commentCount)) + " ms/comment)");

            lastTime = System.currentTimeMillis();
            final long userCount = new UserDemographics().execute();
            ms = System.currentTimeMillis() - lastTime;
            Utilities.printit(true, new Date() + ": UserDemographics took " + (ms / 1000) + " seconds (" + (ms / ((userCount == 0L) ? 1L : userCount)) + " ms/user)");

            lastTime = System.currentTimeMillis();
            final long groupCount = new GroupDemographics().execute();
            ms = System.currentTimeMillis() - lastTime;
            Utilities.printit(true, new Date() + ": GroupDemographics took " + (ms / 1000) + " seconds (" + (ms / ((groupCount == 0L) ? 1L : groupCount)) + " ms/group)");

            lastTime = System.currentTimeMillis();
            final long recentCount = new UpdateRecents().execute();
            ms = System.currentTimeMillis() - lastTime;
            Utilities.printit(true, new Date() + ": UpdateRecents took " + (ms / 1000) + " seconds (" + (ms / ((recentCount == 0L) ? 1L : recentCount)) + " ms/recent)");
        }


        final long timeInMillis = System.currentTimeMillis() - startJob;
        final double millisPerBlah = (blahCount == 0) ? 0 : (timeInMillis * 1.0d) / blahCount;
        Utilities.printit(true, new Date() + ": Pass " + (passCount++) + " completed in " + (timeInMillis / 1000) + " seconds (" + blahCount + " blahs @" + millisPerBlah + " ms/blah)");
    }

    private synchronized String getResourceUsageReport() {
        final OperatingSystemMXBean osbean = getOperatingSystemMXBean();
        final double systemLoadAverage = osbean.getSystemLoadAverage();
        final Runtime runtime = Runtime.getRuntime();
        NumberFormat format = NumberFormat.getInstance();
        long maxMemory = runtime.maxMemory();
        long allocatedMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();

        StringBuilder b = new StringBuilder();
        b.append("System load ave: ");
        b.append(format.format(systemLoadAverage));
        b.append("\n");
        b.append("Free memory: ");
        b.append(format.format(freeMemory / 1024));
        b.append("KB\n");
        b.append("Allocated memory: ");
        b.append(format.format(allocatedMemory / 1024));
        b.append("KB\n");
        b.append("Max memory: ");
        b.append(format.format(maxMemory / 1024));
        b.append("KB\n");
        b.append("Total free memory: ");
        b.append(format.format((freeMemory + (maxMemory - allocatedMemory)) / 1024));
        b.append("KB\n");

        return b.toString();
    }
}


//    private static boolean readConfigFile() {
//        FileInputStream in = null;
//        try {
//            System.out.println("Reading configuration file '" + CONFIGURATION_FILEPATH + "'");
//            if (!new File(CONFIGURATION_FILEPATH).exists()) {
//                System.err.println("WARNING: did not find config file named '" + CONFIGURATION_FILEPATH + "'.");
//                return false;
//            }
//            final Properties config = new Properties();
//            in = new FileInputStream(CONFIGURATION_FILEPATH);
//            config.load(in);
//            Main._verbose = Utilities.safeGetBoolean(config.getProperty(VERBOSE_CONFIG_PROPERTY_NAME), Main._verbose);
//            Main.writeRLogs = Utilities.safeGetBoolean(config.getProperty(WRITE_R_LOG_CONFIG_PROPERTY_NAME), Main.writeRLogs);
//            String hostnames = config.getProperty(DB_HOSTNAMES);
//            if (hostnames == null) {
//                System.err.println("Config file missing " + DB_HOSTNAMES + " property");
//                System.exit(-1);
//            }
//            dbHostnames = Arrays.asList(hostnames.split("\\|"));
//
//            memcachedHostname = config.getProperty(MEMCACHED_HOSTNAME);
//            if (memcachedHostname == null) {
//                System.err.println("Config file missing or invalid " + MEMCACHED_HOSTNAME + " property");
//                System.exit(-1);
//            }
//
//            final String recentStrengthCutoffInDays = config.getProperty(RECENT_STRENGTH_CUTOFF_DAYS_CONFIG_PROPERTY_NAME);
//            Main.recentStrengthCutoffInDays = (recentStrengthCutoffInDays != null) ? new Integer(recentStrengthCutoffInDays.trim()) : 1;
//
//        } catch (Exception e) { // ignore
//            System.err.println("Failed to read configuration file '" + CONFIGURATION_FILEPATH + "'");
//            e.printStackTrace();
//            System.exit(-1);
//        } finally {
//            if (in != null) {
//                try {
//                    in.close();
//                } catch (IOException e) {
//                    System.err.println("Failed to read configuration file '" + CONFIGURATION_FILEPATH + "'");
//                    e.printStackTrace();
//                    System.exit(-1);
//                }
//            }
//        }
//
//        return true;
//    }