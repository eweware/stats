package main.java.com.eweware.stats.help;

import cern.colt.function.DoubleProcedure;
import cern.colt.list.DoubleArrayList;
import cern.jet.stat.Descriptive;
import cern.jet.stat.quantile.DoubleQuantileFinder;
import cern.jet.stat.quantile.QuantileFinderFactory;
import com.mongodb.DBObject;
import main.java.com.eweware.service.base.store.dao.BlahTrackerDAO;
import main.java.com.eweware.service.base.store.dao.BlahTrackerDAOConstants;
import main.java.com.eweware.service.base.store.dao.CommentTrackerDAOConstants;
import main.java.com.eweware.service.base.store.dao.UserTrackerDAOConstants;
import main.java.com.eweware.stats.DBCollections;

import java.io.FileWriter;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * @author rk@post.harvard.edu
 *         Date: 9/30/12 Time: 6:13 PM
 */
public class DumpToR implements BlahTrackerDAOConstants, CommentTrackerDAOConstants, UserTrackerDAOConstants {

    static String graphdir = "/Users/admin/dev/apps/blaghua-stats/graphs/";
    static String filename = "comments-aws.dat";

    private List<String> _dates = new ArrayList<String>();
    private DoubleArrayList _comments = new DoubleArrayList();
    private DoubleArrayList _commentsQuantiles;
    private DoubleArrayList _upVotes = new DoubleArrayList();
    private DoubleArrayList _downVotes = new DoubleArrayList();
    private DoubleArrayList _votes = new DoubleArrayList();
    private DoubleArrayList _views = new DoubleArrayList();
    private DoubleArrayList _opens = new DoubleArrayList();

    private Double count = 0.0;
    private Double commentCount = 0.0;
    private Integer maxComments = 0;
    private Double upVotes = 0.0;
    private Integer maxUpVotes = 0;
    private Double downVotes = 0.0;
    private Integer maxDownVotes = 0;
    private Double votes = 0.0;
    private Integer maxVotes = 0;
    private Double views = 0.0;
    private Integer maxViews = 0;
    private Double opens = 0.0;
    private Integer maxOpens = 0;

    private Double sdComments = 0.0;
    private Double sdUpVotes = 0.0;
    private Double sdDownVotes = 0.0;
    private Double sdVotes = 0.0;
    private Double sdViews = 0.0;
    private Double sdOpens = 0.0;

    private Double aveComments = 0.0;
    private Double aveUpVotes = 0.0;
    private Double aveDownVotes = 0.0;
    private Double aveVotes = 0.0;
    private Double aveViews = 0.0;
    private Double aveOpens = 0.0;

    static FileWriter out;

    public static void main(String[] a) throws Exception {

        out = new FileWriter(graphdir + filename);
        try {
            long start = System.currentTimeMillis();
            new DumpToR().compute_colt().printResults("Printing Colt results");
            System.out.println("Colt took " + (System.currentTimeMillis() - start));

        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    private static void test2() {
        {

            int max_N = -1;
            int quantiles = 4;
            double epsilon = 0.00;
            double delta = 0.000;
            double[] phis = {0, .25, .5, .75};
            DoubleQuantileFinder f =
                    QuantileFinderFactory.newDoubleQuantileFinder(false, max_N, epsilon, delta, quantiles, null);

            double[] confs = {3, 6, 7, 8, 8, 10, 13, 15, 16, 20};
            for (double d : confs) {
                f.add(d);
            }
            System.out.println(new DoubleArrayList(phis));

            DoubleArrayList approxQuantiles = f.quantileElements(new DoubleArrayList(phis));

            NumberFormat nf = NumberFormat.getNumberInstance();
            nf.setMaximumFractionDigits(4);
            System.out.print(approxQuantiles.size() + ": ");
            for (int i = 0; i < approxQuantiles.size(); i++) {
                System.out.print(nf.format(approxQuantiles.get(i)) + " ");
            }
//        System.out.println(approxQuantiles.size()+": "+approxQuantiles);

        }
    }

    private static void test() {

        int max_N = -1;
        int quantiles = 1;
        double epsilon = 0.00;
        double delta = 0.000;
        double[] phis = {0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0};
//    double[] phis = {0.001, 0.01, 0.1, 0.5, 0.9, 0.99, 0.999, 1.0};
//    int max_N = -1;
//    int quantiles = 100;
//    double epsilon = 0.001;
//    double delta = 0.0001;
//    double[] phis = {0.001, 0.01, 0.1, 0.5, 0.9, 0.99, 0.999, 1.0};
        DoubleQuantileFinder f =
                QuantileFinderFactory.newDoubleQuantileFinder(false, max_N, epsilon, delta, quantiles, null);

        double[] confs = {0.001, 0.002, 0.002, 0.002, 0.003, 0.003, 0.003, 0.003, 0.003, 0.003,
                0.01, 0.01, 0.01, 0.01, 0.01, 0, 01, 0.04, 0.05, 0.05, 0.07, 0.4, 1.0};
        for (double d : confs) {
            f.add(d);
        }
        System.out.println(new DoubleArrayList(phis));

        DoubleArrayList approxQuantiles = f.quantileElements(new DoubleArrayList(phis));

        NumberFormat nf = NumberFormat.getNumberInstance();
        nf.setMaximumFractionDigits(4);
        System.out.print(approxQuantiles.size() + ": ");
        for (int i = 0; i < approxQuantiles.size(); i++) {
            System.out.print(nf.format(approxQuantiles.get(i)) + " ");
        }
//        System.out.println(approxQuantiles.size()+": "+approxQuantiles);

    }

    private DumpToR compute_colt() throws Exception {
        colt_build_data();
//        colt_compute_summaries();
        return this;
    }

    private void colt_compute_summaries() {
        aveComments = Descriptive.mean(_comments);
        sdComments = Descriptive.standardDeviation(Descriptive.variance(_comments.size(), Descriptive.sum(_comments), Descriptive.sumOfSquares(_comments)));
        maxComments = new Double(Descriptive.max(_comments)).intValue();
        final DoubleArrayList percents = new DoubleArrayList(new double[]{.2, .4, .6, .95, 1.0});
        final DoubleArrayList quantiles = Descriptive.quantiles(_comments, percents);
        System.out.println("COMMENT QUANTILES: ");
        for (int i = 0; i < quantiles.size(); i++) {
            System.out.println(percents.get(i) + "% of blahs have <= " + quantiles.get(i) + " comments");
        }

        aveUpVotes = Descriptive.mean(_upVotes);
        maxUpVotes = new Double(Descriptive.max(_upVotes)).intValue();
        sdUpVotes = Descriptive.standardDeviation(Descriptive.variance(_upVotes.size(), Descriptive.sum(_upVotes), Descriptive.sumOfSquares(_upVotes)));

        aveDownVotes = Descriptive.mean(_downVotes);
        maxDownVotes = new Double(Descriptive.max(_downVotes)).intValue();
        sdDownVotes = Descriptive.standardDeviation(Descriptive.variance(_downVotes.size(), Descriptive.sum(_downVotes), Descriptive.sumOfSquares(_downVotes)));

        aveVotes = Descriptive.mean(_votes);
        maxVotes = new Double(Descriptive.max(_votes)).intValue();
        sdVotes = Descriptive.standardDeviation(Descriptive.variance(_votes.size(), Descriptive.sum(_votes), Descriptive.sumOfSquares(_votes)));

        aveViews = Descriptive.mean(_views);
        maxViews = new Double(Descriptive.max(_views)).intValue();
        sdViews = Descriptive.standardDeviation(Descriptive.variance(_views.size(), Descriptive.sum(_views), Descriptive.sumOfSquares(_views)));

        aveOpens = Descriptive.mean(_opens);
        maxOpens = new Double(Descriptive.max(_opens)).intValue();
        sdOpens = Descriptive.standardDeviation(Descriptive.variance(_opens.size(), Descriptive.sum(_opens), Descriptive.sumOfSquares(_opens)));
    }

    private void colt_build_data() throws Exception {
        for (DBObject tracker : DBCollections.getInstance().getTrackBlahCol().find()) {
            count++;
            _dates.add(Utilities.getBlahOrCommentTrackerDate((String) tracker.get(BlahTrackerDAO.ID)));
            _comments.add((Integer) tracker.get(BT_COMMENTS));
            _upVotes.add((Integer) tracker.get(BT_UP_VOTES));
            _downVotes.add((Integer) tracker.get(BT_DOWN_VOTES));
            _votes.add((Integer) tracker.get(BT_UP_VOTES) + (Integer) tracker.get(BT_DOWN_VOTES));
            _views.add((Integer) tracker.get(BT_VIEWS));
            _opens.add((Integer) tracker.get(BT_OPENS));
        }
        // Sort for quantile calculations, etc.
//        _comments.sort();
        pout(_downVotes);
//        _upVotes.sort();
//        _downVotes.sort();
//        _votes.sort();
//        _views.sort();
//        _opens.sort();
    }

    DoubleProcedure fileOutProcedure = new DoubleProcedure() {
        @Override
        public boolean apply(double v) {
            try {
                pout(v);
            } catch (IOException e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            }
            return true;
        }
    };


    private void pout(DoubleArrayList list) throws IOException {
        if (out != null) {
            for (int i = 0; i < list.size(); i++) {
                out.write(String.valueOf(_dates.get(i)));
                out.write("\t");
                out.write(String.valueOf(list.get(i)));
                out.write("\n");
            }
//            list.forEach(fileOutProcedure);
        }
    }

    private void pout(Object obj) throws IOException {
        if (out != null) {
            out.write(obj.toString());
            out.write("\n");
        }
    }

    private DumpToR compute_from_db() throws Exception {
        calculateAverages();
        calculateStandardDeviations();

        return this;

//        for (DBObject tracker : DBCollections.getInstance().getTrackBlahCol().find()) {
//            final Integer thisComments = (Integer) tracker.get(BT_COMMENTS);
//            final Double commentScore = (thisComments - aveComments) / sdComments;
//            final Integer thisUp = (Integer) tracker.get(BT_UP_VOTES);
//            final Double upScore = (thisUp - aveUpVotes) / sdUpVotes;
//            final Integer thisDown = (Integer) tracker.get(BT_DOWN_VOTES);
//            final Double downScore = (thisDown - aveDownVotes) / sdDownVotes;
//            final Integer thisVotes = (thisUp + thisDown);
//            final Double voteScore = (thisVotes - aveVotes) / sdVotes;
//            final Integer thisViews = (Integer) tracker.get(BT_VIEWS);
//            final Double viewScore = (thisViews - aveViews) / sdViews;
//            final Integer thisOpens = (Integer) tracker.get(BT_OPENS);
//            final Double openScore = (thisOpens - aveOpens) / sdOpens;
//            p("_comments=" + thisComments + " commentScore=" + Math.round(commentScore));
//            p("thisUp=" + thisUp + " upScore=" + Math.round(upScore));
//            p("thisDown=" + thisDown + " downScore=" + Math.round(downScore));
//            p("thisVotes=" + thisVotes + " voteScore=" + Math.round(voteScore));
//            p("thisViews=" + thisViews + " viewScore=" + Math.round(viewScore));
//            p("thisOpens=" + thisOpens + " openScore=" + Math.round(openScore));
//            System.in.read();
//        }
    }

    private void printResults(String msg) {
        p(msg);
        p("blah tracker count=" + count);
        p("aveComments=" + aveComments);
        p("sdComments=" + sdComments);
        p("maxComments=" + maxComments);
        p("aveUpVotes=" + aveUpVotes);
        p("sdUpVotes=" + sdUpVotes);
        p("maxUpVotes=" + maxUpVotes);
        p("aveDownVotes=" + aveDownVotes);
        p("sdDownVotes=" + sdDownVotes);
        p("maxDownVotes=" + maxDownVotes);
        p("aveViews=" + aveViews);
        p("sdViews=" + sdViews);
        p("maxViews=" + maxViews);
        p("aveOpens=" + aveOpens);
        p("sdOpens=" + sdOpens);
        p("maxOpens=" + maxOpens);
        p("aveVotes=" + aveVotes);
        p("maxVotes=" + maxVotes);
    }

    private void calculateStandardDeviations() throws Exception {
        for (DBObject tracker : DBCollections.getInstance().getTrackBlahCol().find()) {
            final Integer thisComments = (Integer) tracker.get(BT_COMMENTS);
            sdComments += (aveComments - thisComments) * (aveComments - thisComments);
            final Integer thisUp = (Integer) tracker.get(BT_UP_VOTES);
            sdUpVotes += (aveUpVotes - thisUp) * (aveUpVotes - thisUp);
            final Integer thisDown = (Integer) tracker.get(BT_DOWN_VOTES);
            sdDownVotes += (aveDownVotes - thisDown) * (aveDownVotes - thisDown);
            final Integer thisVotes = (thisUp + thisDown);
            sdVotes += (aveVotes - thisVotes) * (aveVotes - thisVotes);
            final Integer thisViews = (Integer) tracker.get(BT_VIEWS);
            sdViews += (aveViews - thisViews) * (aveViews - thisViews);
            final Integer thisOpens = (Integer) tracker.get(BT_OPENS);
            sdOpens += (aveOpens - thisOpens) * (aveOpens - thisOpens);
        }
        sdComments = Math.sqrt(sdComments / count);
        sdUpVotes = Math.sqrt(sdUpVotes / count);
        sdDownVotes = Math.sqrt(sdDownVotes / count);
        sdViews = Math.sqrt(sdViews / count);
        sdOpens = Math.sqrt(sdOpens / count);
    }

    private void calculateAverages() throws Exception {
        for (DBObject tracker : DBCollections.getInstance().getTrackBlahCol().find()) {
            count++;
            final Integer thisComments = (Integer) tracker.get(BT_COMMENTS);
            commentCount += thisComments;
            if (thisComments > maxComments) {
                maxComments = thisComments;
            }

            final Integer thisUp = (Integer) tracker.get(BT_UP_VOTES);
            upVotes += thisUp;
            if (thisUp > maxUpVotes) {
                maxUpVotes = thisUp;
            }

            final Integer thisDown = (Integer) tracker.get(BT_DOWN_VOTES);
            downVotes += thisDown;
            if (thisDown > maxDownVotes) {
                maxDownVotes = thisDown;
            }
            final Integer thisVotes = thisUp + thisDown;
            votes += thisVotes;
            if (thisVotes > maxVotes) {
                maxVotes = thisVotes;
            }

            final Integer thisViews = (Integer) tracker.get(BT_VIEWS);
            views += thisViews;
            if (thisViews > maxViews) {
                maxViews = thisViews;
            }

            final Integer thisOpens = (Integer) tracker.get(BT_OPENS);
            opens += thisOpens;
            if (thisOpens > maxOpens) {
                maxOpens = thisOpens;
            }
        }
        aveComments = commentCount / count;
        aveUpVotes = upVotes / count;
        aveDownVotes = downVotes / count;
        aveVotes = votes / count;
        aveViews = views / count;
        aveOpens = opens / count;
    }

    public static void p(Object s) {
        System.out.println(s);
    }

}
