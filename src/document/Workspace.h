#pragma once

#include "document/Clipboard.h"
#include "document/Document.h"
#include "document/History.h"
#include "layout/GridPlanner.h"
#include "worker/WorkerClient.h"

#include <QObject>
#include <QVector>
#include <functional>

namespace chunkdaddy {

/// A target profile as reported by the worker.
struct ProfileInfo {
    QString id;
    QString displayName;
    QString version;
    int minChunkY = 0;
    int maxChunkY = 0;
    int minBlockY = 0;
    int maxBlockY = 0;
    bool stable = false;
    bool fullyVerified = false;
    QString verificationSummary;
    QString notes;

    /// What this profile means for whoever opens the world.
    ///
    /// Bedrock stamps MinimumCompatibleClientVersion into level.dat. A client or server
    /// older than that refuses the world with "a newer version of the game saved this
    /// world" - the world is intact, the client is simply older than the profile it was
    /// written for. So this is the number that actually decides whether an export opens.
    QString requirementText() const {
        if (version.isEmpty()) {
            return QStringLiteral("No target profile selected.");
        }
        return QStringLiteral(
                   "Stamped as Bedrock %1. Minecraft %1 or newer can open worlds written with "
                   "this profile; an older client or server refuses them with \"a newer version "
                   "of the game saved this world\". Pick the profile that matches the oldest "
                   "build you need to load it with.")
            .arg(version);
    }
};

/// Everything open in the application: documents, the chunk clipboard, and the worker.
///
/// A cut that spans two tabs is one workspace transaction, which is why the clipboard and
/// the cross-document commit live here rather than on an individual document.
class Workspace : public QObject {
    Q_OBJECT

public:
    using Callback = std::function<void(const WorkerReply&)>;

    explicit Workspace(QObject* parent = nullptr);

    bool startWorker(QString* error);
    WorkerClient& worker() noexcept { return *m_worker; }
    Clipboard& clipboard() noexcept { return m_clipboard; }
    History& history() noexcept { return m_history; }

    const QVector<Document*>& documents() const noexcept { return m_documents; }
    Document* documentById(const QString& id) const;
    QVector<ProfileInfo> profiles() const;
    ProfileInfo profile(const QString& id) const;

    // --- document lifecycle ---
    void createVoidWorld(const QString& name, const QString& profileId, Callback done);
    void inspectSource(const QString& path, Callback done);
    void openWorldRoot(const QString& directory, const QString& edition, const QString& name,
                       const QString& profileId, Callback done,
                       WorkerClient::ProgressHandler progress = nullptr);
    void closeDocument(Document* document);

    // --- editing ---
    qint64 importSchematics(const QStringList& paths, Callback done,
                          WorkerClient::ProgressHandler progress = nullptr);
    void setTemplateSpawns(const QString& templateId, const QVector<double>& spawn1,
                           const QVector<double>& spawn2, bool confirmed, Callback done);
    void placeGrid(Document* document, const GridPlan& plan, bool replaceExisting, Callback done);
    void copySelection(Document* document, bool cut, Callback done);
    void pasteClipboard(Document* document, int destChunkX, int destChunkZ, bool replaceExisting,
                        Callback done);
    void moveSelection(Document* document, int deltaChunkX, int deltaChunkZ, bool replaceExisting,
                       Callback done);
    void clearSelection(Document* document, Callback done);
    void undo(Document* document, Callback done);
    void redo(Document* document, Callback done);

    // --- world settings ---
    void setExportRectangle(Document* document, const std::optional<ChunkRect>& rectangle,
                            int borderChunks, Callback done);
    void setWorldSpawn(Document* document, const BlockPos& spawn, Callback done);
    void renameDocument(Document* document, const QString& name, Callback done);

    // --- level settings ---
    //
    // These four all reply with the same payload: the schema plus the settings as they
    // now actually are. The panel redisplays that reply rather than its own idea of what
    // it asked for, so a value the worker clamped or refused is visible immediately.
    //
    // Every mutating call also refreshes the document afterwards, because the world spawn
    // lives in these settings and the viewport draws it.
    void requestLevelSettings(Document* document, Callback done);
    void applyLevelSettings(Document* document, const QJsonObject& values, Callback done);
    void revertLevelSettingsToSource(Document* document, Callback done);
    void adoptLevelSettings(Document* target, Document* source, Callback done);

    /// Re-read a document's state from the worker without changing anything.
    void refreshDocument(Document* document, Callback done);

    // --- output ---
    void validateExport(Document* document, Callback done);
    /// `profileId` empty keeps the document's current target profile.
    void exportWorld(Document* document, const QString& destination, const QString& mode,
                     const QString& worldName, const QString& numberMode, bool arenaPreset,
                     const QString& profileId, Callback done,
                     WorkerClient::ProgressHandler progress = nullptr);

    void requestPreviewTiles(Document* document, const ChunkRect& area, int sliceY,
                             const QString& heightMode, Callback done, int pixelsPerChunk = 16);

signals:
    void documentAdded(Document* document);
    void documentRemoved(Document* document);
    void documentsChanged();

private:
    /// Apply a reply's document state and record the commit in the readable history.
    void applyAndRecord(Document* document, const WorkerReply& reply, const QString& description);
    QJsonObject selectionJson(const Document* document) const;

    WorkerClient* m_worker = nullptr;
    Clipboard m_clipboard;
    History m_history;
    QVector<Document*> m_documents;
};

} // namespace chunkdaddy
