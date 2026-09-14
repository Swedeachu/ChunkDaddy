package gg.swim.chunkdaddy.worker.schematic;

/** Raised when a schematic cannot be accepted; the message is shown to the user. */
public class SchematicFormatException extends RuntimeException {
    public SchematicFormatException(String message) {
        super(message);
    }

    public SchematicFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
