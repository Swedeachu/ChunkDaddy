#include "document/Document.h"

#include <QJsonArray>

namespace chunkdaddy {
namespace {

std::optional<ChunkRect> readRect(const QJsonObject& parent, const QString& name) {
    if (!parent.contains(name) || !parent.value(name).isObject()) {
        return std::nullopt;
    }
    const QJsonObject rect = parent.value(name).toObject();
    return ChunkRect(rect.value(QStringLiteral("minChunkX")).toInt(),
                     rect.value(QStringLiteral("minChunkZ")).toInt(),
                     rect.value(QStringLiteral("maxChunkX")).toInt(),
                     rect.value(QStringLiteral("maxChunkZ")).toInt());
}

std::optional<QVector<double>> readTriple(const QJsonObject& parent, const QString& name) {
    if (!parent.contains(name) || !parent.value(name).isObject()) {
        return std::nullopt;
    }
    const QJsonObject point = parent.value(name).toObject();
    return QVector<double>{point.value(QStringLiteral("x")).toDouble(),
                           point.value(QStringLiteral("y")).toDouble(),
                           point.value(QStringLiteral("z")).toDouble()};
}

} // namespace

TemplateInfo TemplateInfo::fromJson(const QJsonObject& object) {
    TemplateInfo info;
    info.templateId = object.value(QStringLiteral("templateId")).toString();
    info.slug = object.value(QStringLiteral("slug")).toString();
    info.sourceFile = object.value(QStringLiteral("sourceFile")).toString();
    info.sourceSha256 = object.value(QStringLiteral("sourceSha256")).toString();
    info.spongeVersion = object.value(QStringLiteral("spongeVersion")).toInt();
    info.javaDataVersion = object.value(QStringLiteral("javaDataVersion")).toInt();
    info.sizeX = object.value(QStringLiteral("sizeX")).toInt();
    info.sizeY = object.value(QStringLiteral("sizeY")).toInt();
    info.sizeZ = object.value(QStringLiteral("sizeZ")).toInt();
    info.footprintChunksX = object.value(QStringLiteral("footprintChunksX")).toInt();
    info.footprintChunksZ = object.value(QStringLiteral("footprintChunksZ")).toInt();
    info.blockEntityCount = object.value(QStringLiteral("blockEntityCount")).toInt();
    info.paletteSize = object.value(QStringLiteral("paletteSize")).toInt();
    info.aggregateCandidate = object.value(QStringLiteral("aggregateCandidate")).toBool();
    info.spawnsConfirmed = object.value(QStringLiteral("spawnsConfirmed")).toBool();
    info.spawnsAutomatic = object.value(QStringLiteral("spawnsAutomatic")).toBool();
    info.hasWorldEditOrigin = object.value(QStringLiteral("hasWorldEditOrigin")).toBool();
    info.blockingIssueCount = object.value(QStringLiteral("blockingIssueCount")).toInt();
    info.spawnPoint1 = readTriple(object, QStringLiteral("spawnPoint1"));
    info.spawnPoint2 = readTriple(object, QStringLiteral("spawnPoint2"));

    if (object.value(QStringLiteral("schematicOffset")).isObject()) {
        const QJsonObject offset = object.value(QStringLiteral("schematicOffset")).toObject();
        info.schematicOffset = BlockPos{offset.value(QStringLiteral("x")).toInt(),
                                        offset.value(QStringLiteral("y")).toInt(),
                                        offset.value(QStringLiteral("z")).toInt()};
    }

    for (const QJsonValue& value : object.value(QStringLiteral("issues")).toArray()) {
        const QJsonObject issue = value.toObject();
        info.issueLines.append(QStringLiteral("[%1] %2 %3 - %4")
                                   .arg(issue.value(QStringLiteral("severity")).toString(),
                                        issue.value(QStringLiteral("kind")).toString(),
                                        issue.value(QStringLiteral("identifier")).toString(),
                                        issue.value(QStringLiteral("detail")).toString()));
    }
    return info;
}

QString TemplateInfo::statusText() const {
    QStringList parts;
    parts << QStringLiteral("Sponge v%1").arg(spongeVersion);
    parts << QStringLiteral("%1x%2x%3").arg(sizeX).arg(sizeY).arg(sizeZ);
    parts << QStringLiteral("%1x%2 chunks").arg(footprintChunksX).arg(footprintChunksZ);
    parts << (spawnsAutomatic ? QStringLiteral("automatic centre/surface spawns")
              : spawnsConfirmed ? QStringLiteral("spawns confirmed") : QStringLiteral("spawns NOT confirmed"));
    if (blockingIssueCount > 0) {
        parts << QStringLiteral("%1 blocking issue(s)").arg(blockingIssueCount);
    }
    if (aggregateCandidate) {
        parts << QStringLiteral("looks like an aggregate");
    }
    return parts.join(QStringLiteral(" • "));
}

ArenaInfo ArenaInfo::fromJson(const QJsonObject& object) {
    ArenaInfo info;
    info.id = object.value(QStringLiteral("id")).toString();
    info.exportId = object.value(QStringLiteral("exportId")).toString();
    info.templateId = object.value(QStringLiteral("templateId")).toString();
    info.templateSlug = object.value(QStringLiteral("templateSlug")).toString();
    info.minX = object.value(QStringLiteral("minX")).toInt();
    info.minY = object.value(QStringLiteral("minY")).toInt();
    info.minZ = object.value(QStringLiteral("minZ")).toInt();
    info.needsRevalidation = object.value(QStringLiteral("needsRevalidation")).toBool();
    if (const auto rect = readRect(object, QStringLiteral("chunkBounds"))) {
        info.chunkBounds = *rect;
    }
    return info;
}

Document::Document(QObject* parent) : QObject(parent) {}

void Document::applyState(const QJsonObject& state) {
    if (state.contains(QStringLiteral("documentId"))) {
        m_documentId = state.value(QStringLiteral("documentId")).toString();
    }
    m_name = state.value(QStringLiteral("name")).toString(m_name);
    m_profileId = state.value(QStringLiteral("profileId")).toString(m_profileId);
    m_revision = static_cast<qint64>(state.value(QStringLiteral("revision")).toDouble(m_revision));
    m_dirty = state.value(QStringLiteral("dirty")).toBool(m_dirty);
    m_canUndo = state.value(QStringLiteral("canUndo")).toBool(false);
    m_canRedo = state.value(QStringLiteral("canRedo")).toBool(false);
    m_materializedColumns = state.value(QStringLiteral("materializedColumns")).toInt();
    m_hasExplicitExportRectangle =
        state.value(QStringLiteral("hasExplicitExportRectangle")).toBool(false);
    m_exportBorderChunks = state.value(QStringLiteral("exportBorderChunks")).toInt();

    m_contentBounds = readRect(state, QStringLiteral("contentBounds"));
    m_exportRectangle = readRect(state, QStringLiteral("exportRectangle"));

    if (state.value(QStringLiteral("worldSpawn")).isObject()) {
        const QJsonObject spawn = state.value(QStringLiteral("worldSpawn")).toObject();
        m_worldSpawn = BlockPos{spawn.value(QStringLiteral("x")).toInt(),
                                spawn.value(QStringLiteral("y")).toInt(),
                                spawn.value(QStringLiteral("z")).toInt()};
    }

    if (state.contains(QStringLiteral("templates"))) {
        m_templates.clear();
        for (const QJsonValue& value : state.value(QStringLiteral("templates")).toArray()) {
            m_templates.append(TemplateInfo::fromJson(value.toObject()));
        }
    }
    if (state.contains(QStringLiteral("arenas"))) {
        m_arenas.clear();
        for (const QJsonValue& value : state.value(QStringLiteral("arenas")).toArray()) {
            m_arenas.append(ArenaInfo::fromJson(value.toObject()));
        }
    }
    emit stateChanged();
}

const ArenaInfo* Document::arenaAt(int chunkX, int chunkZ) const {
    for (const ArenaInfo& arena : m_arenas) {
        if (arena.chunkBounds.contains(chunkX, chunkZ)) {
            return &arena;
        }
    }
    return nullptr;
}

bool Document::expandSelectionToArenas() {
    if (m_selection.isEmpty()) {
        return false;
    }
    bool expanded = false;
    // Selecting part of a registered arena defaults to expanding to that arena's bounds,
    // because a partial move would leave its recorded spawns pointing at nothing.
    for (const ArenaInfo& arena : m_arenas) {
        if (m_selection.containsAny(arena.chunkBounds) && !m_selection.containsAll(arena.chunkBounds)) {
            m_selection.add(arena.chunkBounds);
            expanded = true;
        }
    }
    if (expanded) {
        emit selectionChanged();
    }
    return expanded;
}

QString Document::tabTitle() const {
    return m_dirty ? m_name + QStringLiteral(" *") : m_name;
}

} // namespace chunkdaddy
