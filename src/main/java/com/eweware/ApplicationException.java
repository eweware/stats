package com.eweware;

/**
 * @author rk@post.harvard.edu
 *         Date: 5/8/13 Time: 12:32 PM
 */
public class ApplicationException extends Exception {
    public ApplicationException() {
        super();
    }

    public ApplicationException(String s) {
        super(s);
    }

    public ApplicationException(String s, Throwable throwable) {
        super(s, throwable);
    }

    public ApplicationException(Throwable throwable) {
        super(throwable);
    }
}
