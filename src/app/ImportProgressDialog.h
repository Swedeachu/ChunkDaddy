#pragma once
#include "worker/Protocol.h"
#include <QDialog>
#include <QElapsedTimer>

class QLabel;
class QProgressBar;
class QPushButton;

namespace chunkdaddy {
class ImportProgressDialog : public QDialog {
    Q_OBJECT
public:
    explicit ImportProgressDialog(int fileCount, QWidget* parent = nullptr);
    void updateProgress(const WorkerProgress& progress);
    void preparingGrid();
signals:
    void cancelRequested();
protected:
    void reject() override;
private:
    QLabel* m_file;
    QLabel* m_stage;
    QLabel* m_elapsedLabel;
    QProgressBar* m_progress;
    QPushButton* m_cancel;
    QElapsedTimer m_elapsed;
    bool m_cancelling = false;
};
}
