#include "app/ImportProgressDialog.h"
#include "app/OperationProgressDialog.h"
#include <QLabel>
#include "app/SchematicImportDialog.h"
#include "app/Settings.h"
#include <QCheckBox>
#include <QProgressBar>
#include <QPushButton>
#include <QSignalSpy>
#include <QSpinBox>
#include <QTest>

using namespace chunkdaddy;
class TestImportDialogs : public QObject {
    Q_OBJECT
private slots:
    void worldProgressLayoutAndStages() {
        OperationProgressDialog dialog("Reading PVP_ZONE_FFA.mcworld…");
        dialog.show();
        QTest::qWait(30);
        auto* bar = dialog.findChild<QProgressBar*>("operationProgress");
        auto* stage = dialog.findChild<QLabel*>("operationStage");
        QCOMPARE(bar->maximum(), 0);
        WorkerProgress progress;
        progress.stage = "Reading world chunks";
        progress.done = 1234; progress.total = 2371;
        dialog.updateProgress(progress);
        QCOMPARE(bar->value(), 52);
        progress.done = progress.total;
        dialog.updateProgress(progress);
        QVERIFY(dialog.isVisible());
        QCOMPARE(bar->value(), 100);
        progress.total = 0;
        progress.stage = "Preparing the world preview and finishing the import. Please wait…";
        dialog.updateProgress(progress);
        QCOMPARE(bar->maximum(), 0);
        QTest::qWait(30);
        QVERIFY(stage->geometry().bottom() < bar->geometry().top());
        QCOMPARE(stage->geometry().left(), bar->geometry().left());
        QCOMPARE(stage->geometry().right(), bar->geometry().right());
        QVERIFY(dialog.rect().contains(bar->geometry()));
        if (qEnvironmentVariableIsSet("CHUNKDADDY_UI_SCREENSHOTS"))
            dialog.grab().save("world-progress.png");
        QTest::keyClick(&dialog, Qt::Key_Escape);
        QVERIFY(dialog.isVisible());
        dialog.accept();
    }

    void progressAndCancellation() {
        ImportProgressDialog dialog(15);
        dialog.show();
        QSignalSpy cancel(&dialog, &ImportProgressDialog::cancelRequested);
        WorkerProgress progress;
        progress.fileName = "8-cyberpunk.schem";
        progress.stage = "Mapping blocks (128/410)";
        progress.done = 7;
        progress.total = 15;
        dialog.updateProgress(progress);
        auto* bar = dialog.findChild<QProgressBar*>("importFileProgress");
        QCOMPARE(bar->value(), 7);
        QCOMPARE(bar->maximum(), 15);
        QTest::qWait(100);
        if (qEnvironmentVariableIsSet("CHUNKDADDY_UI_SCREENSHOTS"))
            dialog.grab().save("import-progress.png");
        QTest::keyClick(&dialog, Qt::Key_Escape);
        QCOMPARE(cancel.count(), 1);
        QVERIFY(dialog.isVisible());
        QTest::keyClick(&dialog, Qt::Key_Escape);
        QCOMPARE(cancel.count(), 1);
        dialog.accept();
    }

    void gridStartsBesideWorldAndRejectsOverlap() {
        Document document;
        QJsonObject info;
        info.insert("contentBounds", Protocol::rect(-27, -25, 28, 24));
        document.applyState(info);
        TemplateInfo entry;
        entry.templateId = "template";
        entry.slug = "desert";
        entry.sourceFile = "1-desert.schem";
        entry.sizeX = 198; entry.sizeY = 123; entry.sizeZ = 249;
        entry.footprintChunksX = 13; entry.footprintChunksZ = 16;
        SchematicImportDialog dialog({entry}, &document, -64, 319);
        QVERIFY(dialog.plan().valid);
        QVERIFY(dialog.plan().bounds.minX() > 28);
        QPushButton* place = nullptr;
        for (auto* button : dialog.findChildren<QPushButton*>())
            if (button->text() == "Place Grid") place = button;
        QVERIFY(place);
        QVERIFY(place->isEnabled());
        dialog.show();
        QTest::qWait(100);
        if (qEnvironmentVariableIsSet("CHUNKDADDY_UI_SCREENSHOTS"))
            dialog.grab().save("import-grid.png");
        dialog.findChild<QSpinBox*>("gridOriginX")->setValue(0);
        dialog.findChild<QSpinBox*>("gridOriginZ")->setValue(0);
        QVERIFY(!place->isEnabled());
        for (auto* check : dialog.findChildren<QCheckBox*>())
            if (check->text().startsWith("Replace existing")) check->setChecked(true);
        QVERIFY(place->isEnabled());
    }
};
QTEST_MAIN(TestImportDialogs)
#include "test_importdialogs.moc"
