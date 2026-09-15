#pragma once

#include "worker/Protocol.h"
#include <QDialog>
#include <QElapsedTimer>

class QLabel;
class QProgressBar;

namespace chunkdaddy {
/// Stable layout for world loading and document operations. The worker's reply,
/// not a progress value of 100%, determines when the dialog closes.
class OperationProgressDialog : public QDialog {
    Q_OBJECT
public:
    explicit OperationProgressDialog(const QString& operation, QWidget* parent = nullptr);
    void updateProgress(const WorkerProgress& progress);
protected:
    void reject() override {} // Closing the popup must not hide a running operation.
private:
    QLabel* m_stage;
    QLabel* m_elapsedLabel;
    QProgressBar* m_progress;
    QElapsedTimer m_elapsed;
};
}
