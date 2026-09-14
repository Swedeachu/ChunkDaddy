#include "layout/InstanceNaming.h"

#include <QTest>

using namespace chunkdaddy;

class TestInstanceNaming : public QObject {
    Q_OBJECT

private slots:
    void slugsStripLeadingNumericPrefix() {
        QCOMPARE(InstanceNaming::suggestSlug(QStringLiteral("1-desert.schem")),
                 QStringLiteral("desert"));
        QCOMPARE(InstanceNaming::suggestSlug(QStringLiteral("15-medieval.schem")),
                 QStringLiteral("medieval"));
        QCOMPARE(InstanceNaming::suggestSlug(QStringLiteral("5-tropical-ruins.schem")),
                 QStringLiteral("tropical-ruins"));
        QCOMPARE(InstanceNaming::suggestSlug(QStringLiteral("all_arenas.schem")),
                 QStringLiteral("all-arenas"));
        // A name that is only digits must not become empty.
        QCOMPARE(InstanceNaming::suggestSlug(QStringLiteral("12.schem")), QStringLiteral("12"));
    }

    void slugHandlesPathsAndOddCharacters() {
        QCOMPARE(InstanceNaming::suggestSlug(QStringLiteral("/a/b/8-cyberpunk.schem")),
                 QStringLiteral("cyberpunk"));
        QCOMPARE(InstanceNaming::suggestSlug(QStringLiteral("C:\\maps\\2-Oriental.schem")),
                 QStringLiteral("oriental"));
        QCOMPARE(InstanceNaming::suggestSlug(QStringLiteral("  Winter  Arena .schem")),
                 QStringLiteral("winter-arena"));
    }

    void exportIdsKeepHyphenatedSlugsIntact() {
        const QString id = InstanceNaming::exportId(QStringLiteral("tropical-ruins"), 7);
        QCOMPARE(id, QStringLiteral("tropical-ruins-7"));
        // Splitting on '-' would say the family is "tropical"; the test exists to record
        // that this is wrong and that the template UUID is the real key.
        QVERIFY(id.split(QLatin1Char('-')).first() != QStringLiteral("tropical-ruins"));
    }

    void slugValidation() {
        QVERIFY(InstanceNaming::isValidSlug(QStringLiteral("desert")));
        QVERIFY(InstanceNaming::isValidSlug(QStringLiteral("tropical-ruins")));
        QVERIFY(!InstanceNaming::isValidSlug(QStringLiteral("Desert")));
        QVERIFY(!InstanceNaming::isValidSlug(QStringLiteral("-desert")));
        QVERIFY(!InstanceNaming::isValidSlug(QStringLiteral("desert-")));
        QVERIFY(!InstanceNaming::isValidSlug(QString()));
    }

    void naturalOrderingOfTheProductionFiles() {
        QStringList names{QStringLiteral("10-greek.schem"),  QStringLiteral("2-oriental.schem"),
                          QStringLiteral("1-desert.schem"),  QStringLiteral("15-medieval.schem"),
                          QStringLiteral("9-mine.schem")};
        const QStringList sorted = InstanceNaming::sortNaturally(names);
        QCOMPARE(sorted.at(0), QStringLiteral("1-desert.schem"));
        QCOMPARE(sorted.at(1), QStringLiteral("2-oriental.schem"));
        QCOMPARE(sorted.at(2), QStringLiteral("9-mine.schem"));
        QCOMPARE(sorted.at(3), QStringLiteral("10-greek.schem"));
        QCOMPARE(sorted.at(4), QStringLiteral("15-medieval.schem"));
    }

    void duplicateSlugsAreResolvedBeforePlacement() {
        QSet<QString> taken{QStringLiteral("desert")};
        QCOMPARE(InstanceNaming::deduplicate(QStringLiteral("desert"), taken),
                 QStringLiteral("desert-2"));
        taken.insert(QStringLiteral("desert-2"));
        QCOMPARE(InstanceNaming::deduplicate(QStringLiteral("desert"), taken),
                 QStringLiteral("desert-3"));
        QCOMPARE(InstanceNaming::deduplicate(QStringLiteral("winter"), taken),
                 QStringLiteral("winter"));
    }
};

QTEST_MAIN(TestInstanceNaming)
#include "test_instancenaming.moc"
