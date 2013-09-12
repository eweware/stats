package main.java.com.eweware;

/**
 * @author rk@post.harvard.edu
 *         Date: 4/20/13 Time: 2:05 PM
 */
public class DBException extends Exception {

    public DBException() {
        super();
    }

    public DBException(String s) {
        super(s);
    }

    public DBException(String s, Throwable throwable) {
        super(s, throwable);
    }

    public DBException(Throwable throwable) {
        super(throwable);
    }
}
