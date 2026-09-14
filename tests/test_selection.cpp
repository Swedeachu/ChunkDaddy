#include "document/Selection.h"

#include <QTest>

using namespace chunkdaddy;

class TestSelection : public QObject {
    Q_OBJECT

private slots:
    void emptySelectionHasNoMembers() {
        Selection selection;
        QVERIFY(selection.isEmpty());
        QVERIFY(!selection.contains(0, 0));
        QCOMPARE(selection.columnCount(), 0LL);
        QVERIFY_THROWS_EXCEPTION(std::logic_error, selection.bounds());
    }

    void additiveRectangles() {
        Selection selection;
        selection.add(ChunkRect(0, 0, 1, 1));
        selection.add(ChunkRect(4, 4, 5, 5));
        QCOMPARE(selection.columnCount(), 8LL);
        QVERIFY(selection.contains(0, 0));
        QVERIFY(selection.contains(5, 5));
        QVERIFY(!selection.contains(2, 2));

        const ChunkRect bounds = selection.bounds();
        QCOMPARE(bounds.minX(), 0);
        QCOMPARE(bounds.maxX(), 5);
    }

    void subtractionRemovesColumns() {
        Selection selection;
        selection.set(ChunkRect(0, 0, 3, 3));
        QCOMPARE(selection.columnCount(), 16LL);
        selection.subtract(ChunkRect(1, 1, 2, 2));
        QCOMPARE(selection.columnCount(), 12LL);
        QVERIFY(!selection.contains(1, 1));
        QVERIFY(selection.contains(0, 0));
        QVERIFY(selection.contains(3, 3));
    }

    void negativeCoordinateSelection() {
        Selection selection;
        selection.set(ChunkRect::fromCorners(2, 2, -3, -3));
        QCOMPARE(selection.columnCount(), 36LL);
        QVERIFY(selection.contains(-3, -3));
        QVERIFY(selection.contains(-1, 0));
        QVERIFY(!selection.contains(3, 0));
    }

    void containsAllAndAnyForArenaExpansion() {
        Selection selection;
        selection.set(ChunkRect(0, 0, 9, 9));
        const ChunkRect inside(2, 2, 5, 5);
        const ChunkRect straddling(8, 8, 12, 12);
        const ChunkRect outside(20, 20, 22, 22);

        QVERIFY(selection.containsAll(inside));
        QVERIFY(selection.containsAny(inside));
        QVERIFY(!selection.containsAll(straddling));
        QVERIFY(selection.containsAny(straddling));
        QVERIFY(!selection.containsAny(outside));
    }
};

QTEST_MAIN(TestSelection)
#include "test_selection.moc"
