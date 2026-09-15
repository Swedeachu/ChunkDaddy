#include "app/OperationProgressDialog.h"
#include <QLabel>
#include <QProgressBar>
#include <QTimer>
#include <QVBoxLayout>

namespace chunkdaddy {
OperationProgressDialog::OperationProgressDialog(const QString& operation, QWidget* parent)
    : QDialog(parent) {
    setWindowTitle(tr("Working on your world"));
    setWindowModality(Qt::WindowModal);
    setWindowFlag(Qt::WindowCloseButtonHint, false);
    setMinimumWidth(520);
    auto* layout = new QVBoxLayout(this);
    layout->setContentsMargins(24, 24, 24, 24);
    layout->setSpacing(16);
    auto* heading = new QLabel(operation, this);
    heading->setObjectName("operationHeading");
    heading->setTextFormat(Qt::PlainText);
    heading->setWordWrap(true);
    auto headingFont = heading->font();
    headingFont.setBold(true);
    heading->setFont(headingFont);
    layout->addWidget(heading);
    m_stage = new QLabel(tr("Starting…"), this);
    m_stage->setObjectName("operationStage");
    m_stage->setTextFormat(Qt::PlainText);
    m_stage->setWordWrap(true);
    m_stage->setMinimumHeight(m_stage->fontMetrics().lineSpacing() * 2);
    m_stage->setAlignment(Qt::AlignLeft | Qt::AlignTop);
    layout->addWidget(m_stage);
    m_progress = new QProgressBar(this);
    m_progress->setObjectName("operationProgress");
    m_progress->setMinimumHeight(24);
    m_progress->setRange(0, 0);
    m_progress->setTextVisible(false);
    layout->addWidget(m_progress);
    m_elapsedLabel = new QLabel(tr("Elapsed: 0 s"), this);
    layout->addWidget(m_elapsedLabel);
    m_elapsed.start();
    auto* timer = new QTimer(this);
    connect(timer, &QTimer::timeout, this, [this] {
        m_elapsedLabel->setText(tr("Elapsed: %1 s").arg(m_elapsed.elapsed() / 1000));
    });
    timer->start(1000);
    resize(560, sizeHint().height());
}

void OperationProgressDialog::updateProgress(const WorkerProgress& progress) {
    m_stage->setText(progress.describe());
    if (progress.total > 0) {
        m_progress->setRange(0, 100);
        m_progress->setValue(int(progress.fraction() * 100));
        m_progress->setTextVisible(true);
    } else {
        m_progress->setRange(0, 0);
        m_progress->setTextVisible(false);
    }
}
}
