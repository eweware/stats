package main.java.com.eweware.stats;

import main.java.com.eweware.stats.help.Utilities;

import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author rk@post.harvard.edu
 *         Date: 9/15/13 Time: 11:42 AM
 */
public class ReputationThread implements Runnable {

    private static final Logger logger = Logger.getLogger(ReputationThread.class.getCanonicalName());

    private Object lock = new Object();

    private boolean stopped;
    private boolean userReputationDone;
    private String error;
    private static boolean fatalErrorEmailSent;

    @Override
    public void run() {
        long ms;
        try {
            final long startJob = System.currentTimeMillis();

            long lastTime = System.currentTimeMillis();
            new UserDescriptiveStats().execute();
            Utilities.printit(true, new Date() + ": UserDescriptiveStats took " + ((System.currentTimeMillis() - lastTime) / 1000) + " seconds");

            Thread.sleep(1000 * 60 * 5);
            setUserReputationDone();

            lastTime = System.currentTimeMillis();
            final long blahCount = new BlahDemographics().execute();
            Utilities.printit(true, new Date() + ": BlahDemographics took " + ((System.currentTimeMillis() - lastTime) / 1000) + " seconds");

            // Removed because client is not using it
//            lastTime = System.currentTimeMillis();
//            final long commentCount = new CommentDemographics().execute();
//            ms = System.currentTimeMillis() - lastTime;
//            Utilities.printit(true, new Date() + ": CommentDemographics took " + (ms / 1000) + " seconds (" + (ms / ((commentCount == 0L) ? 1L : commentCount)) + " ms/comment)");

            lastTime = System.currentTimeMillis();
            final long userCount = new UserDemographics().execute();
            ms = System.currentTimeMillis() - lastTime;
            Utilities.printit(true, new Date() + ": UserDemographics took " + (ms / 1000) + " seconds (" + (ms / ((userCount == 0L) ? 1L : userCount)) + " ms/user)");

            // Removed because client is not using it
//            lastTime = System.currentTimeMillis();
//            final long groupCount = new GroupDemographics().execute();
//            ms = System.currentTimeMillis() - lastTime;
//            Utilities.printit(true, new Date() + ": GroupDemographics took " + (ms / 1000) + " seconds (" + (ms / ((groupCount == 0L) ? 1L : groupCount)) + " ms/group)");

            final long timeInMillis = System.currentTimeMillis() - startJob;
            final double millisPerBlah = (blahCount == 0) ? 0 : (timeInMillis * 1.0d) / blahCount;
            Utilities.printit(true, new Date() + ": Reputation runtime " + (timeInMillis / 1000) + " seconds (" + blahCount + " blahs @" + millisPerBlah + " ms/blah)");

        } catch (Exception e) {
            setError(e.getMessage());
            logger.log(Level.SEVERE, "Reputation thread error.", e);
            if (!fatalErrorEmailSent) {
                Main.safeSendEmail("Fatal Error", "Fatal error in reputation thread. Check log (~/log/stats.log) for details.<br/><br/>Error: " + e.getMessage() +
                        "<br/><br/>Will restart the thread in the next pass.<br/><br/><b>This message will only be sent this one time!</b>");
                fatalErrorEmailSent = true;
            }
        } finally {
            setStopped(true);
        }
    }

    /**
     * @return Returns true if the process has completed.
     */
    public boolean isStopped() {
        synchronized (lock) {
            return stopped;
        }
    }

    private void setStopped(boolean stopped) {
        synchronized (lock) {
            this.stopped = stopped;
        }
    }

    /**
     * @return Returns an error string if there was an error; else returns null
     */
    public String getError() {
        synchronized (lock) {
            return error;
        }
    }

    private void setError(String error) {
        this.error = error;
    }


    /**
     * The user stats rely on the blah stat computation. It's important
     * that the blah stats are in a consistent state before user stats
     * are calculated. This flag lets the client ensure this.
     * @return Returns true if the user descriptive stats have been calculated.
     */
    public boolean isUserReputationDone() {
        synchronized (lock) {
            return userReputationDone;
        }
    }

    public void setUserReputationDone() {
        synchronized (lock) {
            this.userReputationDone = true;
        }
    }
}
