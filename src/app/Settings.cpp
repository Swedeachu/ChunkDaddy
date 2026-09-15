#include "app/Settings.h"

#include <QSettings>
#include <QStandardPaths>

namespace chunkdaddy {
namespace {

constexpr const char* kLastExportDirectory = "paths/lastExportDirectory";
constexpr const char* kLastImportDirectory = "paths/lastImportDirectory";
constexpr const char* kDefaultCopyCount = "grid/defaultCopyCount";
constexpr const char* kDefaultGapChunks = "grid/defaultGapChunks";
constexpr const char* kLastProfileId = "output/lastProfileId";

QSettings settings() {
    return QSettings(QStringLiteral("SaiCo"), QStringLiteral("ChunkDaddy"));
}

} // namespace

QString Settings::downloadsDirectory() {
    const QString downloads = QStandardPaths::writableLocation(QStandardPaths::DownloadLocation);
    if (!downloads.isEmpty()) {
        return downloads;
    }
    return QStandardPaths::writableLocation(QStandardPaths::HomeLocation);
}

QString Settings::lastExportDirectory() {
    const QString stored = settings().value(QString::fromLatin1(kLastExportDirectory)).toString();
    return stored.isEmpty() ? downloadsDirectory() : stored;
}

void Settings::setLastExportDirectory(const QString& path) {
    settings().setValue(QString::fromLatin1(kLastExportDirectory), path);
}

void Settings::resetExportDirectoryToSystemDefault() {
    settings().remove(QString::fromLatin1(kLastExportDirectory));
}

bool Settings::exportDirectoryIsSystemDefault() {
    return settings().value(QString::fromLatin1(kLastExportDirectory)).toString().isEmpty();
}

QString Settings::lastImportDirectory() {
    const QString stored = settings().value(QString::fromLatin1(kLastImportDirectory)).toString();
    return stored.isEmpty() ? QStandardPaths::writableLocation(QStandardPaths::HomeLocation) : stored;
}

void Settings::setLastImportDirectory(const QString& path) {
    settings().setValue(QString::fromLatin1(kLastImportDirectory), path);
}

int Settings::defaultCopyCount() {
    return settings().value(QString::fromLatin1(kDefaultCopyCount), 30).toInt();
}

void Settings::setDefaultCopyCount(int count) {
    settings().setValue(QString::fromLatin1(kDefaultCopyCount), count);
}

int Settings::defaultGapChunks() {
    return settings().value(QString::fromLatin1(kDefaultGapChunks), 8).toInt();
}

void Settings::setDefaultGapChunks(int chunks) {
    settings().setValue(QString::fromLatin1(kDefaultGapChunks), chunks);
}

QString Settings::lastProfileId() {
    return settings().value(QString::fromLatin1(kLastProfileId)).toString();
}

void Settings::setLastProfileId(const QString& id) {
    settings().setValue(QString::fromLatin1(kLastProfileId), id);
}

} // namespace chunkdaddy
