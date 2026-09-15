#pragma once

#include "document/Selection.h"
#include "layout/Geometry.h"

#include <QJsonObject>
#include <QObject>
#include <QString>
#include <QVector>
#include <optional>

namespace chunkdaddy {

/// A template as the worker reports it.
struct TemplateInfo {
    QString templateId;
    QString slug;
    QString sourceFile;
    QString sourceSha256;
    int spongeVersion = 0;
    int javaDataVersion = 0;
    int sizeX = 0;
    int sizeY = 0;
    int sizeZ = 0;
    int footprintChunksX = 0;
    int footprintChunksZ = 0;
    int blockEntityCount = 0;
    int paletteSize = 0;
    bool aggregateCandidate = false;
    bool spawnsConfirmed = false;
    bool spawnsAutomatic = false;
    bool spawnsReady() const { return spawnsConfirmed || spawnsAutomatic; }
    bool hasWorldEditOrigin = false;
    int blockingIssueCount = 0;
    std::optional<BlockPos> schematicOffset;
    std::optional<QVector<double>> spawnPoint1;
    std::optional<QVector<double>> spawnPoint2;
    QStringList issueLines;

    static TemplateInfo fromJson(const QJsonObject& object);
    QString statusText() const;
};

/// An arena instance as the worker reports it.
struct ArenaInfo {
    QString id;
    QString exportId;
    QString templateId;
    QString templateSlug;
    int minX = 0;
    int minY = 0;
    int minZ = 0;
    ChunkRect chunkBounds;
    bool needsRevalidation = false;

    static ArenaInfo fromJson(const QJsonObject& object);
};

/// The native mirror of one open world.
///
/// The document holds identity, metadata and the user's current view state. It never
/// holds block data: the worker owns that, and duplicating it here would create a second
/// world representation that could disagree with the one being exported.
class Document : public QObject {
    Q_OBJECT

public:
    explicit Document(QObject* parent = nullptr);

    /// Apply a `describe_document` payload from the worker.
    void applyState(const QJsonObject& state);

    const QString& documentId() const noexcept { return m_documentId; }
    const QString& name() const noexcept { return m_name; }
    const QString& profileId() const noexcept { return m_profileId; }
    qint64 revision() const noexcept { return m_revision; }
    bool isDirty() const noexcept { return m_dirty; }
    bool canUndo() const noexcept { return m_canUndo; }
    bool canRedo() const noexcept { return m_canRedo; }
    int materializedColumns() const noexcept { return m_materializedColumns; }

    const QVector<TemplateInfo>& templates() const noexcept { return m_templates; }
    const QVector<ArenaInfo>& arenas() const noexcept { return m_arenas; }

    std::optional<ChunkRect> contentBounds() const noexcept { return m_contentBounds; }
    std::optional<ChunkRect> exportRectangle() const noexcept { return m_exportRectangle; }
    bool hasExplicitExportRectangle() const noexcept { return m_hasExplicitExportRectangle; }
    int exportBorderChunks() const noexcept { return m_exportBorderChunks; }
    BlockPos worldSpawn() const noexcept { return m_worldSpawn; }

    /// True when this world was opened from an existing one, so its settings can be
    /// reverted to whatever that world had.
    bool hasSourceLevelSettings() const noexcept { return m_hasSourceLevelSettings; }

    /// True when the source world's raw level.dat will be merged into the export. This is
    /// what carries tags Chunker has no field for, the experiments compound above all.
    bool preservesSourceLevelData() const noexcept { return m_preservesSourceLevelData; }

    /// Experiments the source world had switched on, by their level.dat names.
    const QStringList& sourceExperiments() const noexcept { return m_sourceExperiments; }

    Selection& selection() noexcept { return m_selection; }
    const Selection& selection() const noexcept { return m_selection; }

    /// The arena whose footprint covers a chunk, if any.
    const ArenaInfo* arenaAt(int chunkX, int chunkZ) const;

    /// Expand a selection so that every arena it touches is fully covered.
    /// Returns false when nothing needed expanding.
    bool expandSelectionToArenas();

    /// Local path of the last saved project, empty when never saved.
    const QString& projectPath() const noexcept { return m_projectPath; }
    void setProjectPath(const QString& path) { m_projectPath = path; }

    QString tabTitle() const;

signals:
    void stateChanged();
    void selectionChanged();

public:
    void notifySelectionChanged() { emit selectionChanged(); }

private:
    QString m_documentId;
    QString m_name;
    QString m_profileId;
    QString m_projectPath;
    qint64 m_revision = 0;
    bool m_dirty = false;
    bool m_canUndo = false;
    bool m_canRedo = false;
    int m_materializedColumns = 0;
    bool m_hasExplicitExportRectangle = false;
    int m_exportBorderChunks = 0;
    BlockPos m_worldSpawn{0, 64, 0};
    bool m_hasSourceLevelSettings = false;
    bool m_preservesSourceLevelData = false;
    QStringList m_sourceExperiments;
    std::optional<ChunkRect> m_contentBounds;
    std::optional<ChunkRect> m_exportRectangle;
    QVector<TemplateInfo> m_templates;
    QVector<ArenaInfo> m_arenas;
    Selection m_selection;
};

} // namespace chunkdaddy
