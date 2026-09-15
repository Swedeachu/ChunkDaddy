#include "document/Workspace.h"

#include <QJsonArray>

namespace chunkdaddy {

Workspace::Workspace(QObject* parent) : QObject(parent), m_clipboard(this), m_history(this) {
    m_worker = new WorkerClient(this);
}

bool Workspace::startWorker(QString* error) {
    return m_worker->start(error);
}

Document* Workspace::documentById(const QString& id) const {
    for (Document* document : m_documents) {
        if (document->documentId() == id) {
            return document;
        }
    }
    return nullptr;
}

QVector<ProfileInfo> Workspace::profiles() const {
    QVector<ProfileInfo> result;
    for (const QJsonValue& value :
         m_worker->capabilities().value(QStringLiteral("targetProfiles")).toArray()) {
        const QJsonObject object = value.toObject();
        ProfileInfo info;
        info.id = object.value(QStringLiteral("id")).toString();
        info.displayName = object.value(QStringLiteral("displayName")).toString();
        info.version = object.value(QStringLiteral("version")).toString();
        info.minChunkY = object.value(QStringLiteral("minChunkY")).toInt();
        info.maxChunkY = object.value(QStringLiteral("maxChunkY")).toInt();
        info.minBlockY = object.value(QStringLiteral("minBlockY")).toInt();
        info.maxBlockY = object.value(QStringLiteral("maxBlockY")).toInt();
        info.stable = object.value(QStringLiteral("stable")).toBool();
        info.fullyVerified = object.value(QStringLiteral("fullyVerified")).toBool();
        info.verificationSummary = object.value(QStringLiteral("verificationSummary")).toString();
        info.notes = object.value(QStringLiteral("notes")).toString();
        result.append(info);
    }
    return result;
}

ProfileInfo Workspace::profile(const QString& id) const {
    for (const ProfileInfo& info : profiles()) {
        if (info.id == id) {
            return info;
        }
    }
    return {};
}

void Workspace::createVoidWorld(const QString& name, const QString& profileId, Callback done) {
    QJsonObject request = Protocol::request(QStringLiteral("new_document"));
    request.insert(QStringLiteral("name"), name);
    request.insert(QStringLiteral("profileId"), profileId);
    m_worker->send(request, [this, done](const WorkerReply& reply) {
        if (reply.ok) {
            auto* document = new Document(this);
            document->applyState(reply.result);
            m_documents.append(document);
            emit documentAdded(document);
            emit documentsChanged();
        }
        if (done) {
            done(reply);
        }
    });
}

void Workspace::inspectSource(const QString& path, Callback done) {
    QJsonObject request = Protocol::request(QStringLiteral("inspect_source"));
    request.insert(QStringLiteral("path"), path);
    m_worker->send(request, std::move(done));
}

void Workspace::openWorldRoot(const QString& directory, const QString& edition, const QString& name,
                              const QString& profileId, Callback done,
                              WorkerClient::ProgressHandler progress) {
    QJsonObject request = Protocol::request(QStringLiteral("open_world"));
    request.insert(QStringLiteral("directory"), directory);
    request.insert(QStringLiteral("edition"), edition);
    request.insert(QStringLiteral("name"), name);
    request.insert(QStringLiteral("profileId"), profileId);
    m_worker->send(
        request,
        [this, done](const WorkerReply& reply) {
            if (reply.ok) {
                auto* document = new Document(this);
                document->applyState(reply.result);
                m_documents.append(document);
                emit documentAdded(document);
                emit documentsChanged();
            }
            if (done) {
                done(reply);
            }
        },
        std::move(progress));
}

void Workspace::closeDocument(Document* document) {
    if (!document) {
        return;
    }
    QJsonObject request = Protocol::request(QStringLiteral("close_document"));
    request.insert(QStringLiteral("documentId"), document->documentId());
    m_worker->send(request, nullptr);

    m_documents.removeAll(document);
    emit documentRemoved(document);
    emit documentsChanged();
    // The clipboard deliberately survives: closing the source tab must not invalidate a
    // selection the user already copied.
    document->deleteLater();
}

qint64 Workspace::importSchematics(const QStringList& paths, Callback done,
                                 WorkerClient::ProgressHandler progress) {
    QJsonObject request = Protocol::request(QStringLiteral("import_schematics"));
    QJsonArray array;
    for (const QString& path : paths) {
        array.append(path);
    }
    request.insert(QStringLiteral("paths"), array);
    return m_worker->send(request, std::move(done), std::move(progress));
}

void Workspace::setTemplateSpawns(const QString& templateId, const QVector<double>& spawn1,
                                  const QVector<double>& spawn2, bool confirmed, Callback done) {
    QJsonObject request = Protocol::request(QStringLiteral("set_template_spawns"));
    request.insert(QStringLiteral("templateId"), templateId);
    if (spawn1.size() == 3) {
        request.insert(QStringLiteral("spawnPoint1"), Protocol::point(spawn1[0], spawn1[1], spawn1[2]));
    }
    if (spawn2.size() == 3) {
        request.insert(QStringLiteral("spawnPoint2"), Protocol::point(spawn2[0], spawn2[1], spawn2[2]));
    }
    request.insert(QStringLiteral("confirmed"), confirmed);
    m_worker->send(request, std::move(done));
}

void Workspace::placeGrid(Document* document, const GridPlan& plan, bool replaceExisting,
                          Callback done) {
    QJsonObject request = Protocol::request(QStringLiteral("place_grid"));
    request.insert(QStringLiteral("documentId"), document->documentId());
    request.insert(QStringLiteral("replaceExisting"), replaceExisting);

    QJsonArray placements;
    for (const GridPlacement& placement : plan.placements) {
        QJsonObject entry;
        entry.insert(QStringLiteral("templateId"), placement.templateId);
        entry.insert(QStringLiteral("slug"), placement.slug);
        entry.insert(QStringLiteral("exportId"), placement.exportId);
        entry.insert(QStringLiteral("ordinal"), placement.ordinal);
        entry.insert(QStringLiteral("minX"), placement.minX);
        entry.insert(QStringLiteral("minY"), placement.minY);
        entry.insert(QStringLiteral("minZ"), placement.minZ);
        entry.insert(QStringLiteral("gridRow"), placement.row);
        entry.insert(QStringLiteral("gridColumn"), placement.column);
        placements.append(entry);
    }
    request.insert(QStringLiteral("placements"), placements);

    const QString description =
        QStringLiteral("Place grid: %1 arena(s)").arg(plan.placements.size());
    m_worker->send(request, [this, document, done, description](const WorkerReply& reply) {
        applyAndRecord(document, reply, description);
        if (done) {
            done(reply);
        }
    });
}

QJsonObject Workspace::selectionJson(const Document* document) const {
    QJsonObject request;
    QJsonArray added;
    for (const ChunkRect& rect : document->selection().addedRects()) {
        added.append(Protocol::rect(rect.minX(), rect.minZ(), rect.maxX(), rect.maxZ()));
    }
    QJsonArray subtracted;
    for (const ChunkRect& rect : document->selection().subtractedRects()) {
        subtracted.append(Protocol::rect(rect.minX(), rect.minZ(), rect.maxX(), rect.maxZ()));
    }
    request.insert(QStringLiteral("add"), added);
    request.insert(QStringLiteral("subtract"), subtracted);
    return request;
}

void Workspace::copySelection(Document* document, bool cut, Callback done) {
    QJsonObject request = selectionJson(document);
    request.insert(QStringLiteral("type"), QStringLiteral("copy_selection"));
    request.insert(QStringLiteral("documentId"), document->documentId());
    request.insert(QStringLiteral("cut"), cut);

    const QString sourceId = document->documentId();
    m_worker->send(request, [this, sourceId, cut, done](const WorkerReply& reply) {
        if (reply.ok) {
            const QJsonObject bounds = reply.result.value(QStringLiteral("relativeBounds")).toObject();
            m_clipboard.set(reply.result.value(QStringLiteral("clipboardId")).toString(), sourceId,
                            ChunkRect(bounds.value(QStringLiteral("minChunkX")).toInt(),
                                      bounds.value(QStringLiteral("minChunkZ")).toInt(),
                                      bounds.value(QStringLiteral("maxChunkX")).toInt(),
                                      bounds.value(QStringLiteral("maxChunkZ")).toInt()),
                            static_cast<qint64>(reply.result.value(QStringLiteral("columns")).toDouble()),
                            reply.result.value(QStringLiteral("arenas")).toInt(), cut);
        }
        if (done) {
            done(reply);
        }
    });
}

void Workspace::pasteClipboard(Document* document, int destChunkX, int destChunkZ,
                               bool replaceExisting, Callback done) {
    QJsonObject request = Protocol::request(QStringLiteral("paste_clipboard"));
    request.insert(QStringLiteral("documentId"), document->documentId());
    request.insert(QStringLiteral("clipboardId"), m_clipboard.clipboardId());
    request.insert(QStringLiteral("destChunkX"), destChunkX);
    request.insert(QStringLiteral("destChunkZ"), destChunkZ);
    request.insert(QStringLiteral("replaceExisting"), replaceExisting);

    const bool wasCut = m_clipboard.isPendingCut();
    m_worker->send(request, [this, document, done, wasCut](const WorkerReply& reply) {
        applyAndRecord(document, reply, QStringLiteral("Paste chunks"));
        if (reply.ok && wasCut) {
            // The cut has now committed, so the source has been cleared by the same
            // workspace transaction. Refresh the source tab if it is still open.
            const QString sourceId = reply.result.value(QStringLiteral("sourceDocumentId")).toString();
            if (Document* source = documentById(sourceId)) {
                QJsonObject refresh = Protocol::request(QStringLiteral("document_info"));
                refresh.insert(QStringLiteral("documentId"), sourceId);
                m_worker->send(refresh, [source](const WorkerReply& info) {
                    if (info.ok) {
                        source->applyState(info.result);
                    }
                });
            }
            m_clipboard.clear();
        }
        if (done) {
            done(reply);
        }
    });
}

void Workspace::moveSelection(Document* document, int deltaChunkX, int deltaChunkZ,
                              bool replaceExisting, Callback done) {
    QJsonObject request = selectionJson(document);
    request.insert(QStringLiteral("type"), QStringLiteral("move_selection"));
    request.insert(QStringLiteral("documentId"), document->documentId());
    request.insert(QStringLiteral("deltaChunkX"), deltaChunkX);
    request.insert(QStringLiteral("deltaChunkZ"), deltaChunkZ);
    request.insert(QStringLiteral("replaceExisting"), replaceExisting);

    const QString description =
        QStringLiteral("Move selection by %1, %2 chunks").arg(deltaChunkX).arg(deltaChunkZ);
    m_worker->send(request, [this, document, done, description](const WorkerReply& reply) {
        applyAndRecord(document, reply, description);
        if (done) {
            done(reply);
        }
    });
}

void Workspace::clearSelection(Document* document, Callback done) {
    QJsonObject request = selectionJson(document);
    request.insert(QStringLiteral("type"), QStringLiteral("clear_selection"));
    request.insert(QStringLiteral("documentId"), document->documentId());
    m_worker->send(request, [this, document, done](const WorkerReply& reply) {
        applyAndRecord(document, reply, QStringLiteral("Delete selected chunks"));
        if (done) {
            done(reply);
        }
    });
}

void Workspace::undo(Document* document, Callback done) {
    QJsonObject request = Protocol::request(QStringLiteral("undo"));
    request.insert(QStringLiteral("documentId"), document->documentId());
    m_worker->send(request, [this, document, done](const WorkerReply& reply) {
        if (reply.ok) {
            document->applyState(reply.result);
            m_history.recordUndo(document->revision());
        }
        if (done) {
            done(reply);
        }
    });
}

void Workspace::redo(Document* document, Callback done) {
    QJsonObject request = Protocol::request(QStringLiteral("redo"));
    request.insert(QStringLiteral("documentId"), document->documentId());
    m_worker->send(request, [this, document, done](const WorkerReply& reply) {
        if (reply.ok) {
            document->applyState(reply.result);
            m_history.recordRedo(document->revision());
        }
        if (done) {
            done(reply);
        }
    });
}

void Workspace::setExportRectangle(Document* document, const std::optional<ChunkRect>& rectangle,
                                   int borderChunks, Callback done) {
    QJsonObject request = Protocol::request(QStringLiteral("set_export_rectangle"));
    request.insert(QStringLiteral("documentId"), document->documentId());
    request.insert(QStringLiteral("borderChunks"), borderChunks);
    if (rectangle) {
        request.insert(QStringLiteral("rectangle"),
                       Protocol::rect(rectangle->minX(), rectangle->minZ(), rectangle->maxX(),
                                      rectangle->maxZ()));
    }
    m_worker->send(request, [document, done](const WorkerReply& reply) {
        if (reply.ok) {
            document->applyState(reply.result);
        }
        if (done) {
            done(reply);
        }
    });
}

void Workspace::setWorldSpawn(Document* document, const BlockPos& spawn, Callback done) {
    QJsonObject request = Protocol::request(QStringLiteral("set_world_spawn"));
    request.insert(QStringLiteral("documentId"), document->documentId());
    request.insert(QStringLiteral("x"), spawn.x);
    request.insert(QStringLiteral("y"), spawn.y);
    request.insert(QStringLiteral("z"), spawn.z);
    m_worker->send(request, [document, done](const WorkerReply& reply) {
        if (reply.ok) {
            document->applyState(reply.result);
        }
        if (done) {
            done(reply);
        }
    });
}

void Workspace::renameDocument(Document* document, const QString& name, Callback done) {
    QJsonObject request = Protocol::request(QStringLiteral("set_document_name"));
    request.insert(QStringLiteral("documentId"), document->documentId());
    request.insert(QStringLiteral("name"), name);
    m_worker->send(request, [document, done](const WorkerReply& reply) {
        if (reply.ok) {
            document->applyState(reply.result);
        }
        if (done) {
            done(reply);
        }
    });
}

void Workspace::validateExport(Document* document, Callback done) {
    QJsonObject request = Protocol::request(QStringLiteral("validate_export"));
    request.insert(QStringLiteral("documentId"), document->documentId());
    m_worker->send(request, std::move(done));
}

void Workspace::exportWorld(Document* document, const QString& destination, const QString& mode,
                            const QString& worldName, const QString& numberMode, bool arenaPreset,
                            const QString& profileId, Callback done,
                            WorkerClient::ProgressHandler progress) {
    QJsonObject request = Protocol::request(QStringLiteral("export_world"));
    request.insert(QStringLiteral("documentId"), document->documentId());
    if (!profileId.isEmpty()) {
        request.insert(QStringLiteral("profileId"), profileId);
    }
    request.insert(QStringLiteral("destination"), destination);
    request.insert(QStringLiteral("mode"), mode);
    request.insert(QStringLiteral("worldName"), worldName);
    request.insert(QStringLiteral("numberMode"), numberMode);
    request.insert(QStringLiteral("arenaPreset"), arenaPreset);
    request.insert(QStringLiteral("writeCompanionJson"), true);
    m_worker->send(request, std::move(done), std::move(progress));
}

void Workspace::requestPreviewTiles(Document* document, const ChunkRect& area, int sliceY,
                                    const QString& heightMode, Callback done, int pixelsPerChunk) {
    QJsonObject request = Protocol::request(QStringLiteral("preview_tiles"));
    request.insert(QStringLiteral("documentId"), document->documentId());
    request.insert(QStringLiteral("area"),
                   Protocol::rect(area.minX(), area.minZ(), area.maxX(), area.maxZ()));
    request.insert(QStringLiteral("sliceY"), sliceY);
    request.insert(QStringLiteral("heightMode"), heightMode);
    request.insert(QStringLiteral("pixelsPerChunk"), pixelsPerChunk);
    m_worker->send(request, std::move(done));
}

void Workspace::applyAndRecord(Document* document, const WorkerReply& reply,
                               const QString& description) {
    if (!reply.ok) {
        return;
    }
    document->applyState(reply.result);
    m_history.recordCommit(description, document->revision());
}

} // namespace chunkdaddy
