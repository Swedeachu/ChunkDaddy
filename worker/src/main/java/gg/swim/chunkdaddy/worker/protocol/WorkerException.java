package gg.swim.chunkdaddy.worker.protocol;

/** An error with a structured code the native side can branch on. */
public class WorkerException extends RuntimeException {
    private final String code;

    public WorkerException(String code, String message) {
        super(message);
        this.code = code;
    }

    public WorkerException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
