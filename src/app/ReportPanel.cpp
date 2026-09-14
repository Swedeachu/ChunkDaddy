#include "app/ReportPanel.h"

#include <QPlainTextEdit>
#include <QTabWidget>
#include <QVBoxLayout>

namespace chunkdaddy {
namespace {

QPlainTextEdit* makeView(QWidget* parent) {
    auto* view = new QPlainTextEdit(parent);
    view->setReadOnly(true);
    view->setLineWrapMode(QPlainTextEdit::NoWrap);
    view->setMaximumBlockCount(20000);
    return view;
}

} // namespace

ReportPanel::ReportPanel(QWidget* parent) : QWidget(parent) {
    m_tabs = new QTabWidget(this);
    m_report = makeView(this);
    m_log = makeView(this);
    m_history = makeView(this);

    m_tabs->addTab(m_report, tr("Conversion report"));
    m_tabs->addTab(m_log, tr("Worker log"));
    m_tabs->addTab(m_history, tr("History"));

    auto* layout = new QVBoxLayout(this);
    layout->setContentsMargins(0, 0, 0, 0);
    layout->addWidget(m_tabs);
}

void ReportPanel::appendReport(const QString& text) {
    m_report->appendPlainText(text);
}

void ReportPanel::setReport(const QString& text) {
    m_report->setPlainText(text);
}

void ReportPanel::appendLog(const QString& line) {
    m_log->appendPlainText(line);
}

void ReportPanel::setHistory(const QStringList& entries) {
    m_history->setPlainText(entries.join(QLatin1Char('\n')));
}

void ReportPanel::showReportTab() {
    m_tabs->setCurrentWidget(m_report);
}

} // namespace chunkdaddy
