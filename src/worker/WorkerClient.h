#pragma once

#include "worker/Protocol.h"

#include <QByteArray>
#include <QHash>
#include <QJsonObject>
#include <QObject>
#include <QProcess>
#include <QString>
#include <functional>

namespace chunkdaddy {

/// Owns the worker process and the request/response correlation.
///
/// The process boundary is deliberate: a crash in format handling cannot take the editor
/// with it, and the last committed revision survives. Requests carry an id, replies and
/// progress events carry it back, so a completion message lost to a restart cannot be
/// mistaken for a second placement.
class WorkerClient : public QObject {
    Q_OBJECT

public:
    using ReplyHandler = std::function<void(const WorkerReply&)>;
    using ProgressHandler = std::function<void(const WorkerProgress&)>;

    explicit WorkerClient(QObject* parent = nullptr);
    ~WorkerClient() override;

    /// Locate the bundled runtime and jar, then start the worker.
    /// Returns false and sets `error` when the worker cannot be launched.
    bool start(QString* error);
    void stop();
    bool isRunning() const;

    /// Send a request. `handler` is invoked on the GUI thread when the reply arrives.
    /// Returns the request id, which is also the job id for cancellation.
    qint64 send(QJsonObject request, ReplyHandler handler, ProgressHandler progress = nullptr);

    /// Ask the worker to cancel a running job. Cancellation is answered out of band, so
    /// it never queues behind the job it is cancelling.
    void cancel(qint64 jobId);

    const QJsonObject& capabilities() const noexcept { return m_capabilities; }
    const QString& workspacePath() const noexcept { return m_workspace; }

    /// Directory the worker jar is expected in, resolved from the application directory.
    static QString defaultWorkerJarPath();
    static QString defaultJavaExecutable();

signals:
    /// Emitted when the worker exits unexpectedly. The editor keeps its last committed
    /// state; the caller decides whether to restart.
    void workerFailed(const QString& reason);
    void logLine(const QString& line);
    void capabilitiesChanged();

private slots:
    void onReadyReadStandardOutput();
    void onReadyReadStandardError();
    void onFinished(int exitCode, QProcess::ExitStatus status);

private:
    struct Pending {
        ReplyHandler reply;
        ProgressHandler progress;
    };

    void handleFrame(const QJsonObject& frame);
    void failAllPending(const QString& reason);

    QProcess* m_process = nullptr;
    QByteArray m_buffer;
    QHash<qint64, Pending> m_pending;
    QJsonObject m_capabilities;
    QString m_workspace;
    qint64 m_nextRequestId = 1;
    bool m_stopping = false;
};

} // namespace chunkdaddy
