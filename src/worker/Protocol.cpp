#include "worker/Protocol.h"

namespace chunkdaddy {

QString WorkerReply::describeError() const {
    if (ok) {
        return QString();
    }
    if (errorCode.isEmpty()) {
        return errorMessage;
    }
    return QStringLiteral("%1 (%2)").arg(errorMessage, errorCode);
}

double WorkerProgress::fraction() const {
    if (total <= 0) {
        return 0.0;
    }
    const double value = static_cast<double>(done) / static_cast<double>(total);
    return value < 0.0 ? 0.0 : (value > 1.0 ? 1.0 : value);
}

QString WorkerProgress::describe() const {
    if (total <= 0) {
        return stage;
    }
    return QStringLiteral("%1 %2 / %3").arg(stage).arg(done).arg(total);
}

QJsonObject Protocol::request(const QString& type) {
    QJsonObject object;
    object.insert(QStringLiteral("type"), type);
    return object;
}

QJsonObject Protocol::rect(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
    QJsonObject object;
    object.insert(QStringLiteral("minChunkX"), minChunkX);
    object.insert(QStringLiteral("minChunkZ"), minChunkZ);
    object.insert(QStringLiteral("maxChunkX"), maxChunkX);
    object.insert(QStringLiteral("maxChunkZ"), maxChunkZ);
    return object;
}

QJsonObject Protocol::point(double x, double y, double z) {
    QJsonObject object;
    object.insert(QStringLiteral("x"), x);
    object.insert(QStringLiteral("y"), y);
    object.insert(QStringLiteral("z"), z);
    return object;
}

QJsonObject Protocol::blockPoint(int x, int y, int z) {
    QJsonObject object;
    object.insert(QStringLiteral("x"), x);
    object.insert(QStringLiteral("y"), y);
    object.insert(QStringLiteral("z"), z);
    return object;
}

WorkerReply Protocol::parseReply(const QJsonObject& frame) {
    WorkerReply reply;
    reply.ok = frame.value(QStringLiteral("ok")).toBool(false);
    if (reply.ok) {
        reply.result = frame.value(QStringLiteral("result")).toObject();
    } else {
        const QJsonObject error = frame.value(QStringLiteral("error")).toObject();
        reply.errorCode = error.value(QStringLiteral("code")).toString();
        reply.errorMessage = error.value(QStringLiteral("message")).toString();
        if (reply.errorMessage.isEmpty()) {
            reply.errorMessage = QStringLiteral("The worker reported a failure with no message.");
        }
    }
    return reply;
}

WorkerProgress Protocol::parseProgress(const QJsonObject& payload) {
    WorkerProgress progress;
    progress.stage = payload.value(QStringLiteral("stage")).toString();
    progress.fileName = payload.value(QStringLiteral("fileName")).toString();
    progress.done = static_cast<qint64>(payload.value(QStringLiteral("done")).toDouble());
    progress.total = static_cast<qint64>(payload.value(QStringLiteral("total")).toDouble());
    return progress;
}

bool Protocol::isEvent(const QJsonObject& frame) {
    return frame.contains(QStringLiteral("event"));
}

} // namespace chunkdaddy
