#pragma once

#include <QWidget>

class QPlainTextEdit;
class QTabWidget;

namespace chunkdaddy {

/// Bottom dock: conversion diagnostics, worker log and the edit history.
///
/// Advanced diagnostics live here rather than in the main workflow, but they are never
/// hidden: an import that approximated or refused something has to be visible.
class ReportPanel : public QWidget {
    Q_OBJECT

public:
    explicit ReportPanel(QWidget* parent = nullptr);

    void appendReport(const QString& text);
    void setReport(const QString& text);
    void appendLog(const QString& line);
    void setHistory(const QStringList& entries);

    void showReportTab();

private:
    QTabWidget* m_tabs = nullptr;
    QPlainTextEdit* m_report = nullptr;
    QPlainTextEdit* m_log = nullptr;
    QPlainTextEdit* m_history = nullptr;
};

} // namespace chunkdaddy
