#include "view/ChunkView.h"
#include "document/Workspace.h"
#include <QDataStream>
#include <QElapsedTimer>
#include <QFile>
#include <QSignalSpy>
#include <QTemporaryDir>
#include <QDir>
#include <QJsonArray>
#include <QJsonDocument>
#include <QTest>

using namespace chunkdaddy;

class TestViewPreview : public QObject {
    Q_OBJECT
    static QJsonObject state(const QString& id, int revision, const ChunkRect& bounds) {
        const auto area = Protocol::rect(bounds.minX(), bounds.minZ(), bounds.maxX(), bounds.maxZ());
        return {{"documentId", id}, {"revision", revision}, {"contentBounds", area}, {"exportRectangle", area}};
    }
    static QString tileFile(const QString& path, const ChunkRect& area, int pixels, quint32 color = 0xff558844) {
        QFile file(path);
        if (!file.open(QIODevice::WriteOnly)) return {};
        QDataStream out(&file);
        out.setByteOrder(QDataStream::BigEndian);
        out << quint32(0x43444154) << quint32(2) << qint32(area.minX()) << qint32(area.minZ())
            << qint32(area.widthChunks()) << qint32(area.lengthChunks()) << qint32(pixels);
        for (qint64 c = 0; c < area.columnCount(); ++c) {
            out << quint8(2);
            for (int p = 0; p < pixels * pixels; ++p) out << color;
        }
        return path;
    }
private slots:
    void realWorldAndGridPreview() {
        const QString source = qEnvironmentVariable("CHUNKDADDY_REAL_WORLD");
        if (source.isEmpty()) QSKIP("Set CHUNKDADDY_REAL_WORLD to exercise a local world through the bundled worker.");
        Workspace workspace;
        QString error;
        QVERIFY2(workspace.startWorker(&error), qPrintable(error));
        WorkerReply reply;
        bool done = false;
        auto received = [&](const WorkerReply& value) { reply = value; done = true; };
        workspace.inspectSource(source, received);
        QTRY_VERIFY_WITH_TIMEOUT(done, 60000);
        QVERIFY2(reply.ok, qPrintable(reply.describeError()));
        const auto world = reply.result["worlds"].toArray().first().toObject();
        done = false;
        workspace.openWorldRoot(world["directory"].toString(), world["edition"].toString(), "Preview test",
                                "bedrock-1.26.50", received);
        QTRY_VERIFY_WITH_TIMEOUT(done, 60000);
        QVERIFY2(reply.ok, qPrintable(reply.describeError()));
        auto* document = workspace.documents().last();
        ChunkView view;
        view.resize(1200, 800);
        view.show();
        QSignalSpy failed(&view, &ChunkView::previewFailed);
        int requests = 0, active = 0;
        qint64 maxReplyMs = 0;
        connect(&view, &ChunkView::tilesNeeded, &view, [&](const ChunkRect& area, int pixels, quint64 generation) {
            ++requests;
            QCOMPARE(++active, 1);
            QElapsedTimer timer;
            timer.start();
            const auto revision = document->revision();
            workspace.requestPreviewTiles(document, area, view.heightSlice(), view.heightMode(),
                [&, generation, revision, timer](const WorkerReply& value) {
                    --active;
                    maxReplyMs = std::max(maxReplyMs, timer.elapsed());
                    const QString path = value.result["path"].toString();
                    view.completePreview(generation, path, revision, value.ok ? QString() : value.describeError());
                    if (!path.isEmpty()) QFile::remove(path);
                }, pixels);
        });
        QElapsedTimer elapsed;
        elapsed.start();
        view.setDocument(document);
        const auto originalBounds = *document->contentBounds();
        QTRY_VERIFY_WITH_TIMEOUT(view.tiles().hasRegion(originalBounds), 60000);
        qInfo() << "FFA preview ms" << elapsed.elapsed() << "requests" << requests;
        QPoint sourceChunk;
        bool found = false;
        for (int x = originalBounds.minX(); x <= originalBounds.maxX() && !found; ++x)
            for (int z = originalBounds.minZ(); z <= originalBounds.maxZ() && !found; ++z)
                if (view.tiles().state(x, z) == ColumnState::Content) { sourceChunk = {x, z}; found = true; }
        QVERIFY(found);
        const auto originalImage = view.tiles().chunkImage(sourceChunk.x(), sourceChunk.y());
        document->selection().set(ChunkRect(sourceChunk.x(), sourceChunk.y(), sourceChunk.x(), sourceChunk.y()));
        done = false;
        workspace.clearSelection(document, received);
        QTRY_VERIFY_WITH_TIMEOUT(done, 30000);
        QVERIFY2(reply.ok, qPrintable(reply.describeError()));
        QTRY_VERIFY_WITH_TIMEOUT(view.tiles().hasRegion(*document->contentBounds()), 60000);
        QVERIFY(view.tiles().state(sourceChunk.x(), sourceChunk.y()) != ColumnState::Content);
        done = false;
        workspace.undo(document, received);
        QTRY_VERIFY_WITH_TIMEOUT(done, 30000);
        QVERIFY2(reply.ok, qPrintable(reply.describeError()));
        QTRY_VERIFY_WITH_TIMEOUT(view.tiles().hasRegion(originalBounds), 60000);
        QCOMPARE(view.tiles().chunkImage(sourceChunk.x(), sourceChunk.y()), originalImage);
        done = false;
        workspace.redo(document, received);
        QTRY_VERIFY_WITH_TIMEOUT(done, 30000);
        QVERIFY2(reply.ok, qPrintable(reply.describeError()));
        QTRY_VERIFY_WITH_TIMEOUT(view.tiles().hasRegion(*document->contentBounds()), 60000);
        QVERIFY(view.tiles().state(sourceChunk.x(), sourceChunk.y()) != ColumnState::Content);
        done = false;
        workspace.undo(document, received);
        QTRY_VERIFY_WITH_TIMEOUT(done, 30000);
        QVERIFY2(reply.ok, qPrintable(reply.describeError()));

        const QDir schematics(qEnvironmentVariable("CHUNKDADDY_REAL_SCHEMATICS"));
        if (qEnvironmentVariableIsSet("CHUNKDADDY_REAL_SCHEMATICS")) {
            QStringList paths;
            for (const auto& file : schematics.entryList({"*.schem"}, QDir::Files))
                if (file != "all_arenas.schem") paths << schematics.filePath(file);
            done = false;
            workspace.importSchematics(paths, received);
            QTRY_VERIFY_WITH_TIMEOUT(done, 60000);
            QVERIFY2(reply.ok, qPrintable(reply.describeError()));
            QVERIFY(reply.result["failures"].toArray().isEmpty());
            QVector<GridTemplate> templates;
            for (const auto& value : reply.result["templates"].toArray()) {
                const auto t = TemplateInfo::fromJson(value.toObject());
                QVERIFY(t.spawnsAutomatic);
                QVERIFY(t.spawnsReady());
                QVERIFY(t.spawnPoint1.has_value());
                QCOMPARE(t.spawnPoint1, t.spawnPoint2);
                templates.append({t.templateId, t.slug, t.footprintChunksX, t.footprintChunksZ, t.sizeY, 30, -64});
            }
            GridOptions options;
            options.gapChunksX = options.gapChunksZ = 8;
            options.originChunkX = originalBounds.maxX() + 9;
            options.originChunkZ = originalBounds.minZ();
            const auto plan = GridPlanner::plan(templates, options);
            QVERIFY(plan.valid);
            QCOMPARE(plan.totalInstances, 450);
            done = false;
            workspace.placeGrid(document, plan, false, received);
            QTRY_VERIFY_WITH_TIMEOUT(done, 60000);
            QVERIFY2(reply.ok, qPrintable(reply.describeError()));
            QCOMPARE(document->materializedColumns(), 2371);
            done = false;
            workspace.validateExport(document, received);
            QTRY_VERIFY_WITH_TIMEOUT(done, 30000);
            QVERIFY2(reply.ok, qPrintable(reply.describeError()));
            QVERIFY2(reply.result["problems"].toArray().isEmpty(), qPrintable(QString::fromUtf8(QJsonDocument(reply.result).toJson())));
            QCOMPARE(reply.result["automaticSpawnArenaCount"].toInt(), 450);
            elapsed.restart();
            const int before = requests;
            view.zoomToFit(*document->contentBounds());
            QTRY_VERIFY_WITH_TIMEOUT(view.tiles().hasRegion(*document->contentBounds()), 120000);
            qInfo() << "450 arena overview ms" << elapsed.elapsed() << "requests" << requests - before
                    << "cached columns" << view.tiles().cachedChunkCount() << "max batch ms" << maxReplyMs;
        }
        QCOMPARE(failed.count(), 0);
        if (qEnvironmentVariableIsSet("CHUNKDADDY_UI_SCREENSHOTS")) view.grab().save("world-preview.png");
    }

    void loadsLargeFitWithoutScrollingAndRefreshesEditsAndHeightMode() {
        QTemporaryDir temp;
        Document document;
        const ChunkRect bounds(0, 0, 199, 199);
        document.applyState(state("one", 1, bounds));
        ChunkView view;
        view.resize(640, 480);
        view.show();
        int requests = 0;
        connect(&view, &ChunkView::tilesNeeded, &view, [&](const ChunkRect& area, int pixels, quint64 generation) {
            ++requests;
            QVERIFY(area.columnCount() <= 1024);
            QCOMPARE(pixels, 4);
            const auto path = tileFile(temp.filePath("tiles"), area, pixels);
            QTimer::singleShot(0, &view, [&, generation, path] {
                view.completePreview(generation, path, document.revision(), {});
            });
        });
        view.setDocument(&document);
        QTRY_VERIFY_WITH_TIMEOUT(view.tiles().hasRegion(bounds), 10000);
        QCOMPARE(requests, 49);
        const int loaded = requests;
        document.applyState(state("one", 1, bounds));
        QTest::qWait(100);
        QCOMPARE(requests, loaded); // Metadata refresh preserves cached pixels.
        document.applyState(state("one", 2, bounds));
        QTRY_VERIFY_WITH_TIMEOUT(view.tiles().hasRegion(bounds), 10000);
        QVERIFY(requests > loaded);
        const int edited = requests;
        view.setHeightMode("SLICE");
        QTRY_VERIFY_WITH_TIMEOUT(view.tiles().hasRegion(bounds), 10000);
        QVERIFY(requests > edited);
    }

    void detailDropsOnlyAfterZoomingTwiceAsFarOut() {
        const int sizes[] = {50, 100, 300};
        const int expected[] = {16, 4, 1};
        for (int i = 0; i < 3; ++i) {
            Document document;
            document.applyState(state("detail", 1, ChunkRect(0, 0, sizes[i] - 1, sizes[i] - 1)));
            ChunkView view;
            view.resize(640, 480);
            view.show();
            QSignalSpy requests(&view, &ChunkView::tilesNeeded);
            view.setDocument(&document);
            QTRY_COMPARE(requests.count(), 1);
            QCOMPARE(requests.first()[1].toInt(), expected[i]);
        }
    }

    void staleReplyCannotPaintAnotherDocumentAndErrorsCanRetry() {
        QTemporaryDir temp;
        Document first, second;
        const ChunkRect bounds(0, 0, 5, 5);
        first.applyState(state("first", 1, bounds));
        second.applyState(state("second", 1, bounds));
        ChunkView view;
        QSignalSpy requests(&view, &ChunkView::tilesNeeded);
        QSignalSpy errors(&view, &ChunkView::previewFailed);
        view.setDocument(&first);
        QTRY_COMPARE(requests.count(), 1);
        const quint64 oldGeneration = requests.first()[2].toULongLong();
        view.setDocument(&second);
        QTest::qWait(100);
        QCOMPARE(requests.count(), 1); // At most one worker request, even across tabs.
        view.completePreview(oldGeneration, tileFile(temp.filePath("old"), bounds, 16), 1, {});
        QCOMPARE(view.tiles().cachedChunkCount(), 0);
        QTRY_COMPARE(requests.count(), 2);
        auto generation = requests.last()[2].toULongLong();
        view.completePreview(generation, {}, 1, "worker failed");
        QCOMPARE(errors.count(), 1);
        QTest::qWait(100);
        QCOMPARE(requests.count(), 2); // No unbounded failure/retry loop.
        view.retryPreview();
        QTRY_COMPARE(requests.count(), 3);
        generation = requests.last()[2].toULongLong();
        const auto area = qvariant_cast<ChunkRect>(requests.last()[0]);
        const int pixels = requests.last()[1].toInt();
        view.completePreview(generation, tileFile(temp.filePath("new"), area, pixels), 1, {});
        QVERIFY(view.tiles().hasRegion(bounds));
    }

    void heightChangeDiscardsInFlightResponseAndPauseDoesNotQueueWork() {
        Document document;
        const ChunkRect bounds(0, 0, 99, 99);
        document.applyState(state("one", 1, bounds));
        ChunkView view;
        QSignalSpy requests(&view, &ChunkView::tilesNeeded);
        view.setDocument(&document);
        QTRY_COMPARE(requests.count(), 1);
        const auto oldGeneration = requests.first()[2].toULongLong();
        view.setPreviewPaused(true);
        view.setHeightSlice(50);
        view.completePreview(oldGeneration, {}, 1, "stale error");
        QTest::qWait(100);
        QCOMPARE(requests.count(), 1);
        view.setPreviewPaused(false);
        QTRY_COMPARE(requests.count(), 2);
        QVERIFY(requests.last()[2].toULongLong() != oldGeneration);
    }

    void truncatedTileFileDoesNotPartiallyChangeCache() {
        QTemporaryDir temp;
        const auto path = tileFile(temp.filePath("tile"), ChunkRect(0, 0, 1, 0), 16);
        QFile file(path);
        QVERIFY(file.open(QIODevice::ReadWrite));
        QVERIFY(file.resize(file.size() - 50));
        file.close();
        TileCache cache;
        QString error;
        QVERIFY(!cache.loadTileFile(path, &error));
        QVERIFY(!error.isEmpty());
        QCOMPARE(cache.cachedChunkCount(), 0);
    }
};

QTEST_MAIN(TestViewPreview)
#include "test_viewpreview.moc"
