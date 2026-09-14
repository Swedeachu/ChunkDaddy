#include "layout/Geometry.h"

#include <QTest>

using namespace chunkdaddy;

class TestGeometry : public QObject {
    Q_OBJECT

private slots:
    void negativeBlockToChunkUsesFloor() {
        // The classic off-by-one: truncating division puts block -1 in chunk 0.
        QCOMPARE(blockToChunk(-1), -1);
        QCOMPARE(localInChunk(-1), 15);
        QCOMPARE(blockToChunk(-16), -1);
        QCOMPARE(localInChunk(-16), 0);
        QCOMPARE(blockToChunk(-17), -2);
        QCOMPARE(localInChunk(-17), 15);
        QCOMPARE(blockToChunk(0), 0);
        QCOMPARE(blockToChunk(15), 0);
        QCOMPARE(blockToChunk(16), 1);
    }

    void chunkToBlockRoundTrips() {
        for (int chunk = -100; chunk <= 100; ++chunk) {
            QCOMPARE(blockToChunk(chunkToBlock(chunk)), chunk);
            QCOMPARE(blockToChunk(chunkToBlock(chunk) + 15), chunk);
        }
    }

    void chunkKeyRoundTripsNegatives() {
        const int values[] = {-2147483648, -70000, -1, 0, 1, 70000, 2147483647};
        for (int x : values) {
            for (int z : values) {
                const auto key = chunkKey(x, z);
                QCOMPARE(chunkKeyX(key), x);
                QCOMPARE(chunkKeyZ(key), z);
            }
        }
    }

    void ceilDivRejectsBadInput() {
        QCOMPARE(ceilDiv(198, 16), 13);
        QCOMPARE(ceilDiv(249, 16), 16);
        QCOMPARE(ceilDiv(208, 16), 13);
        QCOMPARE(ceilDiv(264, 16), 17);
        QCOMPARE(ceilDiv(16, 16), 1);
        QCOMPARE(ceilDiv(0, 16), 0);
        QVERIFY_THROWS_EXCEPTION(std::invalid_argument, ceilDiv(-1, 16));
        QVERIFY_THROWS_EXCEPTION(std::invalid_argument, ceilDiv(16, 0));
    }

    void checkedMultiplyReportsOverflow() {
        QCOMPARE(mulChecked(336, 479).value(), 160944);
        QVERIFY(!mulChecked(4000000000LL, 4000000000LL).has_value());
    }

    void rectangleCountsColumnsAs64Bit() {
        // The worked example from the design guide: 336 x 479 chunks.
        const ChunkRect grid = ChunkRect::ofSize(0, 0, 336, 479);
        QCOMPARE(grid.widthChunks(), 336);
        QCOMPARE(grid.lengthChunks(), 479);
        QCOMPARE(grid.columnCount(), 160944LL);

        // A rectangle whose product overflows 32 bits must still count correctly.
        const ChunkRect huge = ChunkRect::ofSize(0, 0, 100000, 100000);
        QCOMPARE(huge.columnCount(), 10000000000LL);
    }

    void rectangleHandlesNegativeOrigins() {
        const ChunkRect rect = ChunkRect::fromCorners(5, 5, -5, -5);
        QCOMPARE(rect.minX(), -5);
        QCOMPARE(rect.maxX(), 5);
        QCOMPARE(rect.widthChunks(), 11);
        QVERIFY(rect.contains(-5, -5));
        QVERIFY(rect.contains(5, 5));
        QVERIFY(!rect.contains(6, 0));
        QCOMPARE(rect.minBlock().x, -80);
        QCOMPARE(rect.maxBlock().x, 95);
    }

    void unionAndExpansion() {
        const ChunkRect a(0, 0, 3, 3);
        const ChunkRect b(10, -4, 12, -2);
        const ChunkRect u = a.united(b);
        QCOMPARE(u.minX(), 0);
        QCOMPARE(u.minZ(), -4);
        QCOMPARE(u.maxX(), 12);
        QCOMPARE(u.maxZ(), 3);

        const ChunkRect expanded = a.expanded(2);
        QCOMPARE(expanded.minX(), -2);
        QCOMPARE(expanded.maxX(), 5);
        QVERIFY_THROWS_EXCEPTION(std::invalid_argument, a.expanded(-1));
    }

    void invertedRectangleIsRefused() {
        QVERIFY_THROWS_EXCEPTION(std::invalid_argument, ChunkRect(5, 0, 1, 0));
    }
};

QTEST_MAIN(TestGeometry)
#include "test_geometry.moc"
