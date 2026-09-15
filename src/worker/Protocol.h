#pragma once

#include <QJsonArray>
#include <QJsonObject>
#include <QString>

namespace chunkdaddy {

/// Structured outcome of one worker request.
struct WorkerReply {
    bool ok = false;
    QJsonObject result;
    QString errorCode;
    QString errorMessage;

    QString describeError() const;
};

/// Progress event emitted while a job runs.
struct WorkerProgress {
    QString stage;
    QString fileName;
    qint64 done = 0;
    qint64 total = 0;

    double fraction() const;
    QString describe() const;
};

/// Frame construction and parsing for the JSON Lines worker protocol.
///
/// Requests and events are small structured messages. Image and world payloads are passed
/// as file paths inside the job workspace; sending millions of block records through JSON
/// would be the wrong shape for both sides.
class Protocol {
public:
    static constexpr int kProtocolVersion = 1;

    static QJsonObject request(const QString& type);
    static QJsonObject rect(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ);
    static QJsonObject point(double x, double y, double z);
    static QJsonObject blockPoint(int x, int y, int z);

    static WorkerReply parseReply(const QJsonObject& frame);
    static WorkerProgress parseProgress(const QJsonObject& payload);

    /// True when a frame is an event rather than a reply.
    static bool isEvent(const QJsonObject& frame);
};

} // namespace chunkdaddy
