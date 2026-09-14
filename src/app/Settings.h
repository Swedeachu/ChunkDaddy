#pragma once

#include <QString>

namespace chunkdaddy {

/// Persistent preferences.
///
/// Exports default to the system Downloads folder and then remember the last directory
/// the user actually chose, with an explicit way back to the system default.
class Settings {
public:
    static QString downloadsDirectory();
    static QString lastExportDirectory();
    static void setLastExportDirectory(const QString& path);
    static void resetExportDirectoryToSystemDefault();
    static bool exportDirectoryIsSystemDefault();

    static QString lastImportDirectory();
    static void setLastImportDirectory(const QString& path);

    static int defaultCopyCount();
    static void setDefaultCopyCount(int count);

    static int defaultGapChunks();
    static void setDefaultGapChunks(int chunks);

    static QString lastProfileId();
    static void setLastProfileId(const QString& id);
};

} // namespace chunkdaddy
