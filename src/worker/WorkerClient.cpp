#include "worker/WorkerClient.h"

#include <QCoreApplication>
#include <QDir>
#include <QFileInfo>
#include <QJsonDocument>
#include <QStandardPaths>
#include <QUuid>

namespace chunkdaddy {
namespace {

#ifdef Q_OS_WIN
constexpr const char* kJavaExecutable = "java.exe";
#else
constexpr const char* kJavaExecutable = "java";
#endif

} // namespace

WorkerClient::WorkerClient(QObject* parent) : QObject(parent) {}

WorkerClient::~WorkerClient() {
    stop();
}

QString WorkerClient::defaultWorkerJarPath() {
    const QDir appDir(QCoreApplication::applicationDirPath());
    // Packaged layout first, then the development build tree, so running from a build
    // directory works without copying files around.
    const QStringList candidates = {
        appDir.filePath(QStringLiteral("worker/chunkdaddy-worker.jar")),
        appDir.filePath(QStringLiteral("../worker/chunkdaddy-worker.jar")),
        appDir.filePath(QStringLiteral("../../worker/build/dist/chunkdaddy-worker.jar")),
        appDir.filePath(QStringLiteral("../../../worker/build/dist/chunkdaddy-worker.jar")),
    };
    for (const QString& candidate : candidates) {
        if (QFileInfo::exists(candidate)) {
            return QFileInfo(candidate).absoluteFilePath();
        }
    }
    return QString();
}

QString WorkerClient::defaultJavaExecutable() {
    const QDir appDir(QCoreApplication::applicationDirPath());
    // A bundled runtime means the user never has to install Java themselves.
    const QStringList bundled = {
        appDir.filePath(QStringLiteral("runtime/bin/") + QString::fromLatin1(kJavaExecutable)),
        appDir.filePath(QStringLiteral("../runtime/bin/") + QString::fromLatin1(kJavaExecutable)),
    };
    for (const QString& candidate : bundled) {
        if (QFileInfo::exists(candidate)) {
            return QFileInfo(candidate).absoluteFilePath();
        }
    }
    const QString onPath = QStandardPaths::findExecutable(QString::fromLatin1(kJavaExecutable));
    return onPath;
}

bool WorkerClient::start(QString* error) {
    if (isRunning()) {
        return true;
    }
    const QString jar = defaultWorkerJarPath();
    if (jar.isEmpty()) {
        if (error) {
            *error = QStringLiteral(
                "The conversion worker (chunkdaddy-worker.jar) was not found next to the "
                "application. Build it with: worker/gradlew --project-dir worker installWorker");
        }
        return false;
    }
    const QString java = defaultJavaExecutable();
    if (java.isEmpty()) {
        if (error) {
            *error = QStringLiteral(
                "No Java runtime was found. A packaged ChunkDaddy bundles one; a development "
                "build needs a JDK 21 on PATH.");
        }
        return false;
    }

    m_workspace = QDir(QStandardPaths::writableLocation(QStandardPaths::AppLocalDataLocation))
                      .filePath(QStringLiteral("workspace/")
                                + QUuid::createUuid().toString(QUuid::WithoutBraces));
    QDir().mkpath(m_workspace);

    m_stopping = false;
    m_process = new QProcess(this);
    m_process->setProgram(java);
    m_process->setArguments({QStringLiteral("-XX:+UseZGC"),
                             QStringLiteral("-Dfile.encoding=UTF-8"),
                             QStringLiteral("-jar"), jar,
                             QStringLiteral("--workspace"), m_workspace});
    connect(m_process, &QProcess::readyReadStandardOutput, this,
            &WorkerClient::onReadyReadStandardOutput);
    connect(m_process, &QProcess::readyReadStandardError, this,
            &WorkerClient::onReadyReadStandardError);
    connect(m_process, &QProcess::finished, this, &WorkerClient::onFinished);

    m_process->start();
    if (!m_process->waitForStarted(15000)) {
        if (error) {
            *error = QStringLiteral("The conversion worker did not start: %1")
                         .arg(m_process->errorString());
        }
        m_process->deleteLater();
        m_process = nullptr;
        return false;
    }

    send(Protocol::request(QStringLiteral("capabilities")), [this](const WorkerReply& reply) {
        if (reply.ok) {
            m_capabilities = reply.result;
            emit capabilitiesChanged();
        }
    });
    return true;
}

void WorkerClient::stop() {
    if (!m_process) {
        return;
    }
    m_stopping = true;
    if (m_process->state() != QProcess::NotRunning) {
        QJsonObject shutdown = Protocol::request(QStringLiteral("shutdown"));
        shutdown.insert(QStringLiteral("id"), m_nextRequestId++);
        m_process->write(QJsonDocument(shutdown).toJson(QJsonDocument::Compact) + '\n');
        m_process->closeWriteChannel();
        if (!m_process->waitForFinished(8000)) {
            m_process->kill();
            m_process->waitForFinished(3000);
        }
    }
    m_process->deleteLater();
    m_process = nullptr;
    m_pending.clear();
}

bool WorkerClient::isRunning() const {
    return m_process && m_process->state() == QProcess::Running;
}

qint64 WorkerClient::send(QJsonObject request, ReplyHandler handler, ProgressHandler progress) {
    const qint64 id = m_nextRequestId++;
    request.insert(QStringLiteral("id"), id);

    if (!isRunning()) {
        if (handler) {
            WorkerReply reply;
            reply.ok = false;
            reply.errorCode = QStringLiteral("worker.notRunning");
            reply.errorMessage = QStringLiteral("The conversion worker is not running.");
            handler(reply);
        }
        return id;
    }
    m_pending.insert(id, Pending{std::move(handler), std::move(progress)});
    m_process->write(QJsonDocument(request).toJson(QJsonDocument::Compact) + '\n');
    return id;
}

void WorkerClient::cancel(qint64 jobId) {
    QJsonObject request = Protocol::request(QStringLiteral("cancel"));
    request.insert(QStringLiteral("jobId"), jobId);
    send(std::move(request), nullptr);
}

void WorkerClient::onReadyReadStandardOutput() {
    m_buffer.append(m_process->readAllStandardOutput());
    int newline = m_buffer.indexOf('\n');
    while (newline >= 0) {
        const QByteArray line = m_buffer.left(newline).trimmed();
        m_buffer.remove(0, newline + 1);
        if (!line.isEmpty()) {
            QJsonParseError parseError{};
            const QJsonDocument document = QJsonDocument::fromJson(line, &parseError);
            if (parseError.error == QJsonParseError::NoError && document.isObject()) {
                handleFrame(document.object());
            } else {
                // Anything that is not a frame is a stray log line; surface it rather
                // than dropping it, but never let it desynchronize the stream.
                emit logLine(QString::fromUtf8(line));
            }
        }
        newline = m_buffer.indexOf('\n');
    }
}

void WorkerClient::onReadyReadStandardError() {
    const QByteArray data = m_process->readAllStandardError();
    for (const QByteArray& line : data.split('\n')) {
        const QByteArray trimmed = line.trimmed();
        if (!trimmed.isEmpty()) {
            emit logLine(QString::fromUtf8(trimmed));
        }
    }
}

void WorkerClient::onFinished(int exitCode, QProcess::ExitStatus status) {
    if (m_stopping) {
        return;
    }
    const QString reason =
        status == QProcess::CrashExit
            ? QStringLiteral("The conversion worker stopped unexpectedly. Your last committed "
                             "revision is intact; restart the worker to continue.")
            : QStringLiteral("The conversion worker exited with code %1. Your last committed "
                             "revision is intact.")
                  .arg(exitCode);
    failAllPending(reason);
    emit workerFailed(reason);
}

void WorkerClient::handleFrame(const QJsonObject& frame) {
    const qint64 id = static_cast<qint64>(frame.value(QStringLiteral("id")).toDouble(-1));

    if (Protocol::isEvent(frame)) {
        auto it = m_pending.find(id);
        if (it != m_pending.end() && it->progress
            && frame.value(QStringLiteral("event")).toString() == QStringLiteral("progress")) {
            it->progress(Protocol::parseProgress(frame.value(QStringLiteral("payload")).toObject()));
        }
        return;
    }

    const WorkerReply reply = Protocol::parseReply(frame);
    auto it = m_pending.find(id);
    if (it == m_pending.end()) {
        if (!reply.ok) {
            emit logLine(QStringLiteral("Worker error without a matching request: %1")
                             .arg(reply.describeError()));
        }
        return;
    }
    const Pending pending = it.value();
    m_pending.erase(it);
    if (pending.reply) {
        pending.reply(reply);
    }
}

void WorkerClient::failAllPending(const QString& reason) {
    const QHash<qint64, Pending> pending = m_pending;
    m_pending.clear();
    for (const Pending& entry : pending) {
        if (entry.reply) {
            WorkerReply reply;
            reply.ok = false;
            reply.errorCode = QStringLiteral("worker.died");
            reply.errorMessage = reason;
            entry.reply(reply);
        }
    }
}

} // namespace chunkdaddy
