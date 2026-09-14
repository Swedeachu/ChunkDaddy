package gg.swim.chunkdaddy.worker.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Objects;

/**
 * Versioned JSON Lines transport over the worker's standard streams.
 *
 * <p>Standard output carries protocol frames only. Chunker and the JVM both log to
 * standard output in places, so the real descriptor is captured at startup and
 * {@code System.out} is redirected to standard error; otherwise a stray log line would
 * corrupt the stream the native side is parsing.
 *
 * <p>Large payloads never travel through here. Preview images and world data go through
 * files in the job workspace, and only descriptors are exchanged.
 */
public final class ProtocolIO {
    public static final int PROTOCOL_VERSION = 1;

    private static final Gson GSON = new Gson();

    private final BufferedReader input;
    private final PrintStream output;
    private final Object writeLock = new Object();

    public ProtocolIO(BufferedReader input, PrintStream output) {
        this.input = input;
        this.output = output;
    }

    /** Read the next request frame, or null at end of stream. */
    public JsonObject read() throws IOException {
        String line;
        while ((line = input.readLine()) != null) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            JsonElement parsed;
            try {
                parsed = JsonParser.parseString(trimmed);
            } catch (Exception e) {
                writeParseError(e.getMessage());
                continue;
            }
            if (!parsed.isJsonObject()) {
                writeParseError("Frame is not a JSON object");
                continue;
            }
            return parsed.getAsJsonObject();
        }
        return null;
    }

    public void writeResult(long requestId, JsonObject result) {
        JsonObject frame = new JsonObject();
        frame.addProperty("id", requestId);
        frame.addProperty("ok", true);
        frame.add("result", Objects.requireNonNullElseGet(result, JsonObject::new));
        write(frame);
    }

    public void writeError(long requestId, String code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);

        JsonObject frame = new JsonObject();
        frame.addProperty("id", requestId);
        frame.addProperty("ok", false);
        frame.add("error", error);
        write(frame);
    }

    /** Progress and log events, correlated with the request that caused them. */
    public void writeEvent(long requestId, String event, JsonObject payload) {
        JsonObject frame = new JsonObject();
        frame.addProperty("id", requestId);
        frame.addProperty("event", event);
        frame.add("payload", Objects.requireNonNullElseGet(payload, JsonObject::new));
        write(frame);
    }

    private void writeParseError(String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", "protocol.parse");
        error.addProperty("message", message == null ? "Malformed frame" : message);

        JsonObject frame = new JsonObject();
        frame.addProperty("id", -1);
        frame.addProperty("ok", false);
        frame.add("error", error);
        write(frame);
    }

    private void write(JsonObject frame) {
        String line = GSON.toJson(frame);
        synchronized (writeLock) {
            output.println(line);
            output.flush();
        }
    }
}
