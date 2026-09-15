#include "app/ImportProgressDialog.h"
#include <QLabel>
#include <QProgressBar>
#include <QPushButton>
#include <QTimer>
#include <QVBoxLayout>

namespace chunkdaddy {
ImportProgressDialog::ImportProgressDialog(int fileCount, QWidget* parent) : QDialog(parent) {
    setWindowTitle(tr("Importing schematics"));
    setWindowModality(Qt::WindowModal);
    setMinimumWidth(520);
    auto* layout = new QVBoxLayout(this);
    auto* intro = new QLabel(tr("Loading %1 schematic(s). Grid placement options will open when loading finishes.")
                                 .arg(fileCount), this);
    intro->setWordWrap(true);
    layout->addWidget(intro);
    m_file = new QLabel(tr("Starting import…"), this);
    m_file->setTextFormat(Qt::PlainText);
    m_file->setWordWrap(true);
    m_stage = new QLabel(tr("Waiting for the conversion worker…"), this);
    m_stage->setTextFormat(Qt::PlainText);
    m_stage->setWordWrap(true);
    layout->addWidget(m_file);
    layout->addWidget(m_stage);
    auto* activity = new QProgressBar(this);
    activity->setRange(0, 0);
    activity->setMaximumHeight(8);
    layout->addWidget(activity);
    m_progress = new QProgressBar(this);
    m_progress->setObjectName("importFileProgress");
    m_progress->setRange(0, fileCount);
    m_progress->setValue(0);
    m_progress->setFormat(tr("%v of %m files processed"));
    layout->addWidget(m_progress);
    m_elapsedLabel = new QLabel(this);
    layout->addWidget(m_elapsedLabel);
    m_cancel = new QPushButton(tr("Cancel import"), this);
    layout->addWidget(m_cancel, 0, Qt::AlignRight);
    connect(m_cancel, &QPushButton::clicked, this, &ImportProgressDialog::reject);
    m_elapsed.start();
    auto* timer = new QTimer(this);
    connect(timer, &QTimer::timeout, this, [this] {
        m_elapsedLabel->setText(tr("Elapsed: %1 s").arg(m_elapsed.elapsed() / 1000));
    });
    timer->start(1000);
}

void ImportProgressDialog::updateProgress(const WorkerProgress& progress) {
    m_progress->setValue(static_cast<int>(progress.done));
    if (m_cancelling) return;
    if (!progress.fileName.isEmpty())
        m_file->setText(tr("File %1 of %2 — %3").arg(progress.done + 1).arg(progress.total).arg(progress.fileName));
    m_stage->setText(progress.stage);
}

void ImportProgressDialog::preparingGrid() {
    m_cancel->setEnabled(false);
    m_progress->setValue(m_progress->maximum());
    m_stage->setText(tr("Preparing grid placement options…"));
}

void ImportProgressDialog::reject() {
    if (m_cancelling || !m_cancel->isEnabled()) return;
    m_cancelling = true;
    m_cancel->setEnabled(false);
    m_stage->setText(tr("Cancelling after the current reading step…"));
    emit cancelRequested();
}
}
