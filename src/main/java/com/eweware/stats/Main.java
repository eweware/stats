package main.java.com.eweware.stats;

import main.java.com.eweware.service.base.cache.BlahCache;
import main.java.com.eweware.stats.help.Utilities;

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


    private static final int WAIT_BETWEEN_PASSES_IN_MINUTES = 2;
    private static final int WAIT_BETWEEN_PASSES_IN_MILLIS = 1000 * 60 * WAIT_BETWEEN_PASSES_IN_MINUTES;
    private static final int DEFAULT_MILLIS_TO_WAIT_BEFORE_SENDING_ERROR_EMAIL = 1000 * 10;

    /* Comma-separated list of email recipients.
    Receives errors and startup/stop messages */
    private static final String STATUS_EMAIL_RECIPIENTS = "rk@eweware.com";

    public static boolean _verbose;

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
            }
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

        // Proceed only if there is no reputation thread running or the user stats have been calculated
        final String reputationThreadError = (reputationThread == null) ? null : reputationThread.getError();
        if (reputationThread == null || (reputationThreadError == null && reputationThread.isUserReputationDone())) {

            lastTimeUserReputationDoneInMillis = System.currentTimeMillis();

            long lastTime = System.currentTimeMillis();
            long startJob = lastTime;
            long blahCount = 0L;
            long ms = 0L;

            blahCount = new BlahDescriptiveStats().execute();
            ms = System.currentTimeMillis() - lastTime;
            Utilities.printit(true, new Date() + ": Inboxer BlahDescriptiveStats took " + (ms / 1000) + " seconds (" + (ms / ((blahCount == 0L) ? 1L : blahCount)) + " ms/blah)");

            maybeStartReputationThread();

            lastTime = System.currentTimeMillis();
            new Inboxer().execute();
            Utilities.printit(true, new Date() + ": Inboxer boxing took " + ((System.currentTimeMillis() - lastTime) / 1000) + " seconds");

            lastTime = System.currentTimeMillis();
            final long recentCount = new UpdateRecents().execute();
            ms = System.currentTimeMillis() - lastTime;
            Utilities.printit(true, new Date() + ": Inboxer UpdateRecents took " + (ms / 1000) + " seconds (" + (ms / ((recentCount == 0L) ? 1L : recentCount)) + " ms/recent)");

            final long timeInMillis = System.currentTimeMillis() - startJob;
            final double millisPerBlah = (blahCount == 0) ? 0 : (timeInMillis * 1.0d) / blahCount;
            Utilities.printit(true, new Date() + ": Inboxer pass " + (passCount++) + " done. Runtime " + (timeInMillis / 1000) + " secs (" + blahCount + " blahs @" + millisPerBlah + " ms/blah)");

        } else {
            if (reputationThreadError == null) {
                // Wait for next pass: user stats not yet computed by reputation thread
                final long waitTimeInMillis = System.currentTimeMillis() - lastTimeUserReputationDoneInMillis;
                final String waitedMessage = timeString(waitTimeInMillis);
                Utilities.printit(true, new Date() + ": Inboxer waiting on user reputation. Been waiting for " + waitedMessage);
                if (waitTimeInMillis > millisToWaitForUserReputationBeforeSendingEmail) {
                    millisToWaitForUserReputationBeforeSendingEmail = Utilities.getValueAsLong(1.25 * millisToWaitForUserReputationBeforeSendingEmail) + waitTimeInMillis; // delay next email
                    safeSendEmail("Long wait for user reputation stats", "Been waiting for user reputation to be calculated for " + waitedMessage +
                            " since last notification.<br/><br/>If this condition continues, inbox construction will be slower than usual.<br/><br/>" +
                            "<b>This notification will be sent less frequently, but the condition will persist. Time to deploy a prime-time implementation? Getting a faster/bigger machine won't help.</b>");
                }
            } else {
                // error is logged by the reputation thread, which also sends a one-time email notification
                reputationThread = null;
            }
        }
    }

    private static String timeString(long millis) {
        return new SimpleDateFormat("mm:ss").format(new Date(millis)) + " (minutes:seconds)";
    }

    private void maybeStartReputationThread() {
        if (reputationThread == null || reputationThread.isStopped()) {
            this.reputationThread = new ReputationThread();
            final Thread thread = new Thread(reputationThread);
            thread.setDaemon(true);
            thread.start();
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
