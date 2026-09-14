#include "worker/WorkerClient.h"

#include <QDir>
#include <QSignalSpy>
#include <QStandardPaths>
#include <QTest>

using namespace chunkdaddy;

// Run by setup after staging the worker and runtime beside the executables.
class TestWorkerClient : public QObject {
    Q_OBJECT
private slots:
    void startsBundledWorkerAndCreatesDocument() {
        QStandardPaths::setTestModeEnabled(true);
        WorkerClient client;
        QSignalSpy capabilities(&client, &WorkerClient::capabilitiesChanged);
        QSignalSpy failures(&client, &WorkerClient::workerFailed);
        QString error;
        QVERIFY2(client.start(&error), qPrintable(error));
        QTRY_COMPARE_WITH_TIMEOUT(capabilities.count(), 1, 60000);
        QCOMPARE(failures.count(), 0);
        QCOMPARE(client.capabilities().value("protocolVersion").toInt(), 1);
        QVERIFY(client.defaultJavaExecutable().contains("runtime"));
        bool received = false;
        WorkerReply result;
        client.send(Protocol::request("new_document"), [&](const WorkerReply& reply) {
            result = reply;
            received = true;
        });
        QTRY_VERIFY_WITH_TIMEOUT(received, 30000);
        QVERIFY2(result.ok, qPrintable(result.describeError()));
        QVERIFY(!result.result.value("documentId").toString().isEmpty());
        const QString workspace = client.workspacePath();
        client.stop();
        QVERIFY(!client.isRunning());
        QCOMPARE(failures.count(), 0);
        QVERIFY(QDir(workspace).removeRecursively());
    }
};

QTEST_GUILESS_MAIN(TestWorkerClient)
#include "test_workerclient.moc"
