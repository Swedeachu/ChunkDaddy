#include "layout/GridPlanner.h"

#include <QTest>
#include <QSet>

using namespace chunkdaddy;

namespace {

/// The fifteen production templates, with the footprints recorded in the design guide.
QVector<GridTemplate> productionTemplates(int copies) {
    struct Entry {
        const char* slug;
        int fx;
        int fz;
        int sizeY;
    };
    static const Entry entries[] = {
        {"desert", 13, 16, 123},        {"oriental", 13, 16, 132},
        {"mushroom", 11, 11, 98},       {"winter", 13, 16, 118},
        {"tropical-ruins", 13, 17, 121}, {"aquatic", 13, 16, 120},
        {"mythic", 11, 16, 104},        {"cyberpunk", 13, 16, 161},
        {"mine", 13, 16, 139},          {"greek", 10, 14, 84},
        {"arabic", 13, 16, 102},        {"modern", 13, 16, 132},
        {"pirate", 13, 16, 144},        {"magic", 13, 16, 141},
        {"medieval", 13, 14, 123},
    };
    QVector<GridTemplate> templates;
    for (const Entry& entry : entries) {
        GridTemplate t;
        t.templateId = QString::fromLatin1(entry.slug) + QStringLiteral("-uuid");
        t.slug = QString::fromLatin1(entry.slug);
        t.footprintChunksX = entry.fx;
        t.footprintChunksZ = entry.fz;
        t.sizeY = entry.sizeY;
        t.copyCount = copies;
        t.minY = -32;
        templates.append(t);
    }
    return templates;
}

} // namespace

class TestGridPlanner : public QObject {
    Q_OBJECT

private slots:
    void workedExampleFromTheDesignGuide() {
        // Twenty columns, a four-chunk gap, thirty copies of fifteen maps.
        GridOptions options;
        options.gapChunksX = 4;
        options.gapChunksZ = 4;
        options.columns = 20;

        const GridPlan plan = GridPlanner::plan(productionTemplates(30), options);
        QVERIFY2(plan.valid, qPrintable(plan.error));

        QCOMPARE(plan.totalInstances, 450LL);
        QCOMPARE(plan.columns, 20);
        QCOMPARE(plan.rows, 23);
        // Largest aligned footprint in the collection is 13 x 17 chunks.
        QCOMPARE(plan.cellChunksX, 13);
        QCOMPARE(plan.cellChunksZ, 17);
        QCOMPARE(plan.pitchChunksX, 17);
        QCOMPARE(plan.pitchChunksZ, 21);
        // 20*13 + 19*4 = 336 ; 23*17 + 22*4 = 479
        QCOMPARE(plan.widthChunks, 336);
        QCOMPARE(plan.lengthChunks, 479);
        QCOMPARE(plan.generatedColumns, 160944LL);
        QCOMPARE(plan.placements.size(), 450);
    }

    void gapIsEmptyChunksBetweenFootprints() {
        QVector<GridTemplate> templates;
        GridTemplate t;
        t.templateId = QStringLiteral("t");
        t.slug = QStringLiteral("t");
        t.footprintChunksX = 3;
        t.footprintChunksZ = 3;
        t.sizeY = 16;
        t.copyCount = 4;
        templates.append(t);

        GridOptions options;
        options.gapChunksX = 2;
        options.gapChunksZ = 2;
        options.columns = 2;

        const GridPlan plan = GridPlanner::plan(templates, options);
        QVERIFY(plan.valid);
        QCOMPARE(plan.placements.size(), 4);

        const ChunkRect first = plan.placements[0].footprint();
        const ChunkRect second = plan.placements[1].footprint();
        QCOMPARE(first.maxX(), 2);
        QCOMPARE(second.minX(), 5);
        // Exactly two completely empty chunk columns between the two footprints.
        QCOMPARE(second.minX() - first.maxX() - 1, 2);

        const ChunkRect below = plan.placements[2].footprint();
        QCOMPARE(below.minZ() - first.maxZ() - 1, 2);
    }

    void partialFinalRowIsStillLaidOut() {
        QVector<GridTemplate> templates;
        GridTemplate t;
        t.templateId = QStringLiteral("t");
        t.slug = QStringLiteral("t");
        t.footprintChunksX = 2;
        t.footprintChunksZ = 2;
        t.sizeY = 16;
        t.copyCount = 5;
        templates.append(t);

        GridOptions options;
        options.gapChunksX = 1;
        options.gapChunksZ = 1;
        options.columns = 3;

        const GridPlan plan = GridPlanner::plan(templates, options);
        QVERIFY(plan.valid);
        QCOMPARE(plan.rows, 2);
        QCOMPARE(plan.placements.size(), 5);
        // The rectangle still covers the full 3x2 cell grid, so the unused cell is
        // generated as void rather than left absent.
        QCOMPARE(plan.widthChunks, 3 * 2 + 2 * 1);
        QCOMPARE(plan.lengthChunks, 2 * 2 + 1 * 1);
        QVERIFY(!plan.warnings.isEmpty());
    }

    void mixedFootprintsUseOneUniformCell() {
        QVector<GridTemplate> templates;
        GridTemplate small;
        small.templateId = QStringLiteral("s");
        small.slug = QStringLiteral("small");
        small.footprintChunksX = 2;
        small.footprintChunksZ = 2;
        small.sizeY = 16;
        small.copyCount = 1;
        GridTemplate large;
        large.templateId = QStringLiteral("l");
        large.slug = QStringLiteral("large");
        large.footprintChunksX = 5;
        large.footprintChunksZ = 3;
        large.sizeY = 16;
        large.copyCount = 1;
        templates << small << large;

        GridOptions options;
        options.gapChunksX = 0;
        options.gapChunksZ = 0;
        options.columns = 2;

        const GridPlan plan = GridPlanner::plan(templates, options);
        QVERIFY(plan.valid);
        QCOMPARE(plan.cellChunksX, 5);
        QCOMPARE(plan.cellChunksZ, 3);
        // The small template sits at its cell's minimum corner.
        QCOMPARE(plan.placements[0].minX, 0);
        QCOMPARE(plan.placements[1].minX, 16 * 5);
    }

    void placementsAreChunkAlignedAndNegativeOriginSafe() {
        GridOptions options;
        options.gapChunksX = 4;
        options.gapChunksZ = 4;
        options.columns = 5;
        options.originChunkX = -300;
        options.originChunkZ = -700;

        const GridPlan plan = GridPlanner::plan(productionTemplates(2), options);
        QVERIFY(plan.valid);
        for (const GridPlacement& placement : plan.placements) {
            QCOMPARE(placement.minX & 15, 0);
            QCOMPARE(placement.minZ & 15, 0);
            QCOMPARE(blockToChunk(placement.minX) * 16, placement.minX);
        }
        QCOMPARE(plan.bounds.minX(), -300);
        QCOMPARE(plan.bounds.minZ(), -700);
    }

    void footprintsNeverOverlap() {
        GridOptions options;
        options.gapChunksX = 0;
        options.gapChunksZ = 0;
        options.columns = 7;

        const GridPlan plan = GridPlanner::plan(productionTemplates(3), options);
        QVERIFY(plan.valid);

        QSet<quint64> occupied;
        for (const GridPlacement& placement : plan.placements) {
            const ChunkRect footprint = placement.footprint();
            for (int cx = footprint.minX(); cx <= footprint.maxX(); ++cx) {
                for (int cz = footprint.minZ(); cz <= footprint.maxZ(); ++cz) {
                    const quint64 key = chunkKey(cx, cz);
                    QVERIFY2(!occupied.contains(key),
                             qPrintable(QStringLiteral("overlap at %1,%2").arg(cx).arg(cz)));
                    occupied.insert(key);
                }
            }
        }
    }

    void ordinalsAreAllocatedPerTemplate() {
        const GridPlan plan = GridPlanner::plan(productionTemplates(30), GridOptions{});
        QVERIFY(plan.valid);

        QSet<QString> names;
        QHash<QString, int> counts;
        for (const GridPlacement& placement : plan.placements) {
            QVERIFY2(!names.contains(placement.exportId), qPrintable(placement.exportId));
            names.insert(placement.exportId);
            counts[placement.slug]++;
        }
        QCOMPARE(names.size(), 450);
        QCOMPARE(counts.size(), 15);
        for (auto it = counts.cbegin(); it != counts.cend(); ++it) {
            QCOMPARE(it.value(), 30);
        }
        // A slug containing a hyphen must keep it: splitting on '-' is not a way to
        // recover the template family.
        QVERIFY(names.contains(QStringLiteral("tropical-ruins-1")));
        QVERIFY(names.contains(QStringLiteral("tropical-ruins-30")));
    }

    void suggestedColumnCountIsRoughlySquare() {
        // Pitch 17 x 21 chunks for 450 instances.
        const int columns = GridPlanner::suggestColumns(450, 17, 21, 13, 17);
        QVERIFY(columns > 0);
        const int rows = (450 + columns - 1) / columns;
        const double width = columns * 13.0 + (columns - 1) * 4.0;
        const double length = rows * 17.0 + (rows - 1) * 4.0;
        const double ratio = std::max(width, length) / std::min(width, length);
        QVERIFY2(ratio < 1.25, qPrintable(QStringLiteral("ratio %1 at %2 columns").arg(ratio).arg(columns)));
    }

    void invalidConfigurationsAreRejectedNotThrown() {
        QVERIFY(!GridPlanner::plan({}, GridOptions{}).valid);

        QVector<GridTemplate> templates = productionTemplates(0);
        QVERIFY(!GridPlanner::plan(templates, GridOptions{}).valid);

        GridOptions negativeGap;
        negativeGap.gapChunksX = -1;
        QVERIFY(!GridPlanner::plan(productionTemplates(1), negativeGap).valid);
    }
};

QTEST_MAIN(TestGridPlanner)
#include "test_gridplanner.moc"
