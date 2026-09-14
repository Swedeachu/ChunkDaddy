# Packaging

The goal is a single application the user launches without installing Java, running a
server, opening a browser or typing conversion commands.

A package contains:

```
chunkdaddy(.exe)
worker/chunkdaddy-worker.jar
runtime/                     a trimmed Java runtime (jlink)
<Qt runtime libraries>       windeployqt on Windows, bundled or system Qt on Linux
LICENSES/                    see ../third_party/NOTICES.md
```

`WorkerClient` looks for `worker/chunkdaddy-worker.jar` next to the executable and for
`runtime/bin/java` before falling back to the `java` on `PATH`, so the layout above is what
makes a packaged build self-contained.

- `windows/build-package.ps1` - invokes the root setup/build checks and copies the deployed runtime.
- `linux/build-package.sh` - the same for Linux; Qt remains a system dependency.

Both require a new output directory and refuse to delete an existing one. The root setup
commands are the normal developer entry points; these wrappers additionally assemble a
distribution directory. The Windows package still requires the Microsoft VC++ runtime
on its destination machine. Review third-party licences before redistribution.

Neither script has been run on a clean machine yet. Milestone 6 in the design guide is the
gate: the package must run on a clean supported Windows machine with no JDK installed, and
on a clean supported Linux distribution, with native file dialogs, the Downloads path,
paths containing spaces and non-ASCII characters, and drag and drop all working.
