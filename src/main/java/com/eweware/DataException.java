package main.java.com.eweware;

/**
 * @author rk@post.harvard.edu
 *         Date: 4/20/13 Time: 8:12 PM
 */
public class DataException extends Exception {
    public DataException() {
        super();
    }

    public DataException(String s) {
        super(s);
    }

    public DataException(String s, Throwable throwable) {
        super(s, throwable);
    }

    public DataException(Throwable throwable) {
        super(throwable);
    }
}
