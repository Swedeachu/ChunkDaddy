package gg.swim.chunkdaddy.worker;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import gg.swim.chunkdaddy.worker.protocol.ProtocolIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class WorkerSessionImportTest {
    @TempDir Path workspace;

    @Test
    void failuresStillCompleteFileProgressAndReportEachFile() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        WorkerSession session = session(output);
        JsonObject request = request(7, "import_schematics");
        JsonArray paths = new JsonArray();
        paths.add(workspace.resolve("missing-one.schem").toString());
        paths.add(workspace.resolve("missing-two.schem").toString());
        request.add("paths", paths);
        session.handle(request);
        String[] lines = output.toString(StandardCharsets.UTF_8).strip().split("\\R");
        JsonObject reply = JsonParser.parseString(lines[lines.length - 1]).getAsJsonObject();
        assertTrue(reply.get("ok").getAsBoolean());
        assertEquals(2, reply.getAsJsonObject("result").getAsJsonArray("failures").size());
        assertEquals(0, reply.getAsJsonObject("result").getAsJsonArray("templates").size());
        JsonObject progress = JsonParser.parseString(lines[lines.length - 2]).getAsJsonObject().getAsJsonObject("payload");
        assertEquals(2, progress.get("done").getAsInt());
        assertEquals(2, progress.get("total").getAsInt());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("missing-one.schem"));
    }

    @Test
    void cancellationIsNotReportedAsASuccessfulEmptyImport() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        WorkerSession session = session(output);
        JsonObject cancel = request(1, "cancel");
        cancel.addProperty("jobId", 7);
        session.handle(cancel);
        output.reset();
        JsonObject request = request(7, "import_schematics");
        request.add("paths", new JsonArray());
        session.handle(request);
        JsonObject reply = JsonParser.parseString(output.toString(StandardCharsets.UTF_8)).getAsJsonObject();
        assertFalse(reply.get("ok").getAsBoolean());
        assertEquals("job.cancelled", reply.getAsJsonObject("error").get("code").getAsString());
    }

    private WorkerSession session(ByteArrayOutputStream output) {
        return new WorkerSession(new ProtocolIO(new BufferedReader(new StringReader("")),
                new PrintStream(output, true, StandardCharsets.UTF_8)), workspace);
    }
    private JsonObject request(int id, String type) {
        JsonObject request = new JsonObject();
        request.addProperty("id", id);
        request.addProperty("type", type);
        return request;
    }
}
