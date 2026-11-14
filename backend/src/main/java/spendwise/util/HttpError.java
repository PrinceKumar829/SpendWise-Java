package spendwise.util;

/** An error with an HTTP status code (400 bad input, 404 not found, 409 conflict ...). Same idea as fail() in services/http.js. */
public class HttpError extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public final int status;

    public HttpError(int status, String message) {
        super(message);
        this.status = status;
    }

    /** Use as: throw fail(400, "message"); */
    public static HttpError fail(int status, String message) { return new HttpError(status, message); }
}
