package main.java.com.eweware;

/**
 * @author rk@post.harvard.edu
 *         Date: 4/19/13 Time: 6:28 PM
 */
public class CacheException extends Exception {
    public CacheException() {
        super();
    }

    public CacheException(String s) {
        super(s);
    }

    public CacheException(String s, Throwable throwable) {
        super(s, throwable);
    }

    public CacheException(Throwable throwable) {
        super(throwable);
    }
}
