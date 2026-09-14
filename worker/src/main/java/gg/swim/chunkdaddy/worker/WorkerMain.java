package gg.swim.chunkdaddy.worker;

import com.google.gson.JsonObject;
import gg.swim.chunkdaddy.worker.protocol.ProtocolIO;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Entry point for the ChunkDaddy format worker.
 *
 * <p>The worker is long lived: one process per application session, with bounded task
 * concurrency inside it. Starting a JVM per schematic or per arena would cost more than
 * the work itself and would make the memory budget impossible to reason about.
 *
 * <p>Usage: {@code chunkdaddy-worker --workspace <dir>}. The workspace holds extracted
 * sources, preview tiles and export staging; every path the protocol hands out lives
 * inside it.
 */
public final class WorkerMain {
    public static final String VERSION = "0.1.0";

    private WorkerMain() {
    }

    public static void main(String[] args) throws Exception {
        Path workspace = null;
        for (int i = 0; i < args.length; i++) {
            if ("--workspace".equals(args[i]) && i + 1 < args.length) {
                workspace = Path.of(args[++i]);
            } else if ("--version".equals(args[i])) {
                System.out.println("chunkdaddy-worker " + VERSION);
                return;
            }
        }
        if (workspace == null) {
            workspace = Files.createTempDirectory("chunkdaddy-worker-");
        }
        Files.createDirectories(workspace);

        // Capture the real standard output for protocol frames, then point System.out at
        // standard error. Chunker and several libraries print to System.out; a single such
        // line on the protocol stream would desynchronize the native client.
        PrintStream protocolOut = new PrintStream(
                new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        ProtocolIO io = new ProtocolIO(in, protocolOut);
        WorkerSession session = new WorkerSession(io, workspace);

        // Bounded concurrency: several small requests can be in flight while a long job
        // runs, without letting the process spawn unbounded work.
        int workers = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        ExecutorService executor = Executors.newFixedThreadPool(workers, runnable -> {
            Thread thread = new Thread(runnable, "chunkdaddy-request");
            thread.setDaemon(true);
            return thread;
        });

        try {
            JsonObject request;
            while ((request = io.read()) != null) {
                JsonObject captured = request;
                String type = captured.has("type") ? captured.get("type").getAsString() : "";
                if ("shutdown".equals(type)) {
                    session.handle(captured);
                    break;
                }
                if ("cancel".equals(type)) {
                    // Cancellation must not queue behind the job it is cancelling.
                    session.handle(captured);
                    continue;
                }
                executor.submit(() -> session.handle(captured));
            }
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
            protocolOut.flush();
        }
    }
}
