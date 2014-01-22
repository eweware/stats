package com.eweware.stats;

import com.eweware.service.base.cache.BlahCache;
import com.eweware.stats.help.Utilities;

import java.lang.management.OperatingSystemMXBean;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.management.ManagementFactory.getOperatingSystemMXBean;

/**
 * @author rk@post.harvard.edu
 *         Date: 10/11/12 Time: 9:22 PM
 */
public class Main {

    private static final Logger logger = Logger.getLogger(Main.class.getName());


    private static final int WAIT_BETWEEN_PASSES_IN_MINUTES = 1;
    private static final int WAIT_BETWEEN_PASSES_IN_MILLIS = 1000 * 60 * WAIT_BETWEEN_PASSES_IN_MINUTES;
    private static final int DEFAULT_MILLIS_TO_WAIT_BEFORE_SENDING_ERROR_EMAIL = 1000 * 10;

    /* Comma-separated list of email recipients.
    Receives errors and startup/stop messages */
    private static final String STATUS_EMAIL_RECIPIENTS = "davevr@eweware.com";

    public static boolean _verbose;
    public static String _runconfig;

    // Range in days before today where a strength is considered "recent"
    public static int recentStrengthCutoffInDays = 1;

    private static final List<String> PROD_DB_HOSTNAMES = Arrays.asList(new String[]{"rs1-1.mongo.blahgua.com", "rs1-2.mongo.blahgua.com", "rs1-3.mongo.blahgua.com"});
    private static final List<String> QA_DB_HOSTNAMES = Arrays.asList(new String[]{"qa.db.blahgua.com"});
    private static final List<String> DEV_DB_HOSTNAMES = Arrays.asList(new String[]{"localhost"});

    private static final Integer DEFAULT_DB_PORT = 21191;

    private static List<String> dbHostnames;
    private static String environment; // dev or qa or prod
    private static final Integer _dbPort = DEFAULT_DB_PORT;
    private static String localHostname;    // machine running this app

    private long passCount = 1;  // number of passes of inboxer
    private long lastTimeUserReputationDoneInMillis = System.currentTimeMillis();
    private long millisToWaitForUserReputationBeforeSendingEmail = DEFAULT_MILLIS_TO_WAIT_BEFORE_SENDING_ERROR_EMAIL;

    /**
     * The stats thread calculates the statistics other
     * than blah strength. Since the inboxing procedure
     * is currently only based on blah strength, it should
     * run (as much as possible) independently of the other stat calculations
     * to create new inboxes more frequently.
     */
    private ReputationThread reputationThread;

    public static void main(String[] args) {
        parseArgs(args);
        printConfig();
        try {
            localHostname = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            localHostname = "unknown";
        }

        new Main().run();
    }

    private static boolean parseArgs(String[] args) {
        if (args.length > 0) {
            if (args.length > 1) {
                final String verbose = args[1];
                _verbose = verbose.toLowerCase().equals("true");
                if (args.length > 2) {
                _runconfig = args[2].toLowerCase();
                }
                else _runconfig = "stats_inbox";
            }
            else _runconfig = "stats_inbox";
            environment = args[0].toLowerCase();
            if (environment.equals("prod")) {
                dbHostnames = PROD_DB_HOSTNAMES;
                return true;
            } else if (environment.equals("qa")) {
                dbHostnames = QA_DB_HOSTNAMES;
                return true;
            } else if (environment.equals("dev")) {
                dbHostnames = DEV_DB_HOSTNAMES;
                return true;
            }
        }
        usageAndExit();
        return false;
    }

    private static void printConfig() {
        System.out.println("***** START CONFIGURATION *****");
        System.out.println("EMAIL STATUS RECIPIENT(S): " + STATUS_EMAIL_RECIPIENTS);
        System.out.println("DB HOSTNAME(S): " + dbHostnames);
        System.out.println("DB PORT: " + _dbPort);
        System.out.println("VERBOSE: " + _verbose);
        System.out.println("CONFIG: " + _runconfig);
        System.out.println("****** END CONFIGURATION ******");
    }

    private static void usageAndExit() {
        System.err.println("\nUSAGE: java -jar stats-1.0.0-jar-with-dependencies.jar <environment> [<verbose>]");
        System.err.println("WHERE, <environment> := {prod|qa|dev}\n       <verbose> := {true|false} - optional (DEFAULT: false)");
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

            startupMsg();

            while (true) {
                try {

                    Utilities.printit(true, _verbose ? getResourceUsageReport() : getBriefMemoryReport());

                    runPass();

                } catch (Throwable e) {
                    logger.log(Level.SEVERE, "Failed pass due to unrecoverable error.", e);
                    safeSendEmail("Stats process error", "Failed pass due to unrecoverable error: " + e.getMessage() + "<br/><br/>Check log (~/log/stats.log) for details.");
                    logger.warning("Will try to continue in next pass...");
                }

                Thread.sleep(WAIT_BETWEEN_PASSES_IN_MILLIS);
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Aborted application.", e);
            safeSendEmail("Aborted!", "Aborted application.<br/><br/>Error: " + e.getMessage() + "<br/><br/>Check log (~/log/stats.log) for details.");
            System.exit(-1);
        }
        safeSendEmail("Stopped", "Process stopped.");
        System.exit(0);
    }

    private void startupMsg() {
        final String msg = new Date() + ": Calculating stats on DB host(s) " + dbHostnames;
        Utilities.printit(true, msg);
        safeSendEmail("Started", msg);
    }

    /**
     * Sends email and logs but ignores errors
     */
    public static void safeSendEmail(String subject, String msg) {
        try {
            subject = "Stats Process @" + environment + ": " + subject;
            msg = "Environment '" + environment + "', DB hostname(s): " + dbHostnames + " Stats server hostname '" + localHostname + "'<br/><br/>" + msg;

            Mailer.send(STATUS_EMAIL_RECIPIENTS, subject, msg);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Ignoring mailer failure", e);
        }
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
                safeSendEmail("Shutdown JVM", "Process shut down due to JVM interrupt");
            }
        });
    }

    private void runPass() throws Exception {
        lastTimeUserReputationDoneInMillis = System.currentTimeMillis();
        long lastTime = System.currentTimeMillis();
        long startJob = lastTime;
        long blahCount = 0L;
        long ms = 0L;
        long userCount;

        if (_runconfig.contains("cluster"))
        {
            lastTime = System.currentTimeMillis();
            //new Inboxer().execute();
            //Utilities.printit(true, new Date() + ": User similarity took " + ((System.currentTimeMillis() - lastTime) / 1000) + " seconds");

            lastTime = System.currentTimeMillis();
            userCount = new UserClusterer().execute();
            ms = System.currentTimeMillis() - lastTime;
           Utilities.printit(true, new Date() + ": User Clustering took " + (ms / 1000) + " seconds (" + (ms / ((userCount == 0L) ? 1L : userCount)) + " ms/user)");
        }
        else
            Utilities.printit(true, new Date() + ": Skipping user clustering");

        if (_runconfig.contains("stats"))
        {
            Utilities.printit(true, new Date() + ": Starting Descriptive Stats");
            lastTime = System.currentTimeMillis();
            blahCount = new BlahDescriptiveStats().execute();
            ms = System.currentTimeMillis() - lastTime;
            Utilities.printit(true, new Date() + ": Inboxer BlahDescriptiveStats took " + (ms / 1000) + " seconds (" + (ms / ((blahCount == 0L) ? 1L : blahCount)) + " ms/blah)");

            Utilities.printit(true, new Date() + ": Starting User Reputation Stats");
            lastTime = System.currentTimeMillis();
            executeReputation();
            ms = System.currentTimeMillis() - lastTime;
            Utilities.printit(true, new Date() + ": User Reputation took " + (ms / 1000) + " seconds");
        }
        else
            Utilities.printit(true, new Date() + ": Skipping user and blah stats");

        if (_runconfig.contains("inbox"))
        {
            Utilities.printit(true, new Date() + ": Starting Inboxing");
            lastTime = System.currentTimeMillis();
            new Inboxer().execute();
            Utilities.printit(true, new Date() + ": Inboxer boxing took " + ((System.currentTimeMillis() - lastTime) / 1000) + " seconds");
        }
        else
            Utilities.printit(true, new Date() + ": Skipping inboxing");

    }

    private static String timeString(long millis) {
        return new SimpleDateFormat("mm:ss").format(new Date(millis)) + " (minutes:seconds)";
    }

    private void executeReputation() {
        if (reputationThread == null) {
            reputationThread = new ReputationThread();
        }
        reputationThread.run();

    }

    private void maybeStartReputationThread() {
        if (reputationThread == null || reputationThread.isStopped()) {
            this.reputationThread = new ReputationThread();
            //final Thread thread = new Thread(reputationThread);
            //thread.setDaemon(true);
            //thread.start();
            millisToWaitForUserReputationBeforeSendingEmail = DEFAULT_MILLIS_TO_WAIT_BEFORE_SENDING_ERROR_EMAIL;
        }
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

    private synchronized String getBriefMemoryReport() {
        final Runtime runtime = Runtime.getRuntime();
        NumberFormat format = NumberFormat.getInstance();
        long maxMemory = runtime.maxMemory();
        long allocatedMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();

        StringBuilder b = new StringBuilder();
        b.append("Allocated: ");
        b.append(format.format(allocatedMemory / 1024));
        b.append("KB; ");
        b.append("Free: ");
        b.append(format.format((freeMemory + (maxMemory - allocatedMemory)) / 1024));
        b.append("KB\n");

        return b.toString();
    }
}
