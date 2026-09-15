#include "worker/Protocol.h"

#include <QJsonDocument>
#include <QTest>

using namespace chunkdaddy;

class TestProtocol : public QObject {
    Q_OBJECT

private slots:
    void successfulReply() {
        const QByteArray frame = R"({"id":7,"ok":true,"result":{"documentId":"abc","revision":3}})";
        const WorkerReply reply = Protocol::parseReply(QJsonDocument::fromJson(frame).object());
        QVERIFY(reply.ok);
        QCOMPARE(reply.result.value(QStringLiteral("documentId")).toString(), QStringLiteral("abc"));
    }

    void errorReplyKeepsCodeAndMessage() {
        const QByteArray frame =
            R"({"id":9,"ok":false,"error":{"code":"export.empty","message":"Nothing to export."}})";
        const WorkerReply reply = Protocol::parseReply(QJsonDocument::fromJson(frame).object());
        QVERIFY(!reply.ok);
        QCOMPARE(reply.errorCode, QStringLiteral("export.empty"));
        QVERIFY(reply.describeError().contains(QStringLiteral("export.empty")));
    }

    void errorWithoutMessageStillExplainsItself() {
        const QByteArray frame = R"({"id":1,"ok":false,"error":{"code":"x"}})";
        const WorkerReply reply = Protocol::parseReply(QJsonDocument::fromJson(frame).object());
        QVERIFY(!reply.ok);
        QVERIFY(!reply.errorMessage.isEmpty());
    }

    void eventsAreDistinguishedFromReplies() {
        const QByteArray event =
            R"({"id":4,"event":"progress","payload":{"stage":"writingColumns","fileName":"arena.schem","done":50,"total":200}})";
        const QJsonObject object = QJsonDocument::fromJson(event).object();
        QVERIFY(Protocol::isEvent(object));

        const WorkerProgress progress =
            Protocol::parseProgress(object.value(QStringLiteral("payload")).toObject());
        QCOMPARE(progress.stage, QStringLiteral("writingColumns"));
        QCOMPARE(progress.fileName, QStringLiteral("arena.schem"));
        QCOMPARE(progress.done, 50LL);
        QCOMPARE(progress.total, 200LL);
        QCOMPARE(progress.fraction(), 0.25);
    }

    void progressWithoutTotalDoesNotDivideByZero() {
        WorkerProgress progress;
        progress.stage = QStringLiteral("decoding");
        QCOMPARE(progress.fraction(), 0.0);
        QCOMPARE(progress.describe(), QStringLiteral("decoding"));
    }

    void requestsCarryTheirType() {
        const QJsonObject request = Protocol::request(QStringLiteral("export_world"));
        QCOMPARE(request.value(QStringLiteral("type")).toString(), QStringLiteral("export_world"));

        const QJsonObject rect = Protocol::rect(-5, -7, 3, 4);
        QCOMPARE(rect.value(QStringLiteral("minChunkX")).toInt(), -5);
        QCOMPARE(rect.value(QStringLiteral("maxChunkZ")).toInt(), 4);
    }
};

QTEST_MAIN(TestProtocol)
#include "test_protocol.moc"
