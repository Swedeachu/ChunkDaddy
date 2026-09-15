# Packaging

Release archives are produced by the one-click scripts in `../scripts`:

| Platform | Command | Produces |
| --- | --- | --- |
| Windows x64 | double-click `scripts\release-windows.bat` | `dist\ChunkDaddy-<version>-windows-x64.zip` |
| Linux | `bash scripts/release-linux.sh` | `dist/ChunkDaddy-<version>-linux-<arch>.zip` |

Each builds first and then writes exactly one archive: that file is the whole GitHub
release asset for its platform. `scripts/release.py` does the packaging and documents
what goes in and why.

`build-package.ps1` and `build-package.sh` in this directory are the earlier, superseded
versions of the same idea. They are no longer referenced by anything and can be deleted.
