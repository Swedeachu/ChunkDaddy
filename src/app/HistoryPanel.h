#pragma once

#include <QWidget>

class QLabel;
class QListWidget;
class QPushButton;

namespace chunkdaddy {

class Document;
class History;

/// Side dock: the current document's edit history as a list you can walk back through.
///
/// A read-only transcript answers "what happened" but not "put it back the way it was
/// three steps ago", which is the question a user actually has after a bad grid placement.
/// So every step is clickable, and clicking one asks for exactly the undo or redo calls
/// that reach it. The worker still owns the undo stack; this only decides how many steps
/// to ask it for.
class HistoryPanel : public QWidget {
    Q_OBJECT

public:
    explicit HistoryPanel(QWidget* parent = nullptr);

    void setSources(History* history, Document* document);
    /// Redisplay after a commit, an undo or a tab change.
    void refresh();
    /// Block interaction while an edit is in flight; a second request mid-undo would
    /// race the first.
    void setBusy(bool busy);

signals:
    /// Walk the document to the state in which `appliedSteps` steps are applied.
    void jumpRequested(int appliedSteps);
    void undoRequested();
    void redoRequested();

private:
    void onRowActivated(int row);

    History* m_history = nullptr;
    Document* m_document = nullptr;
    QListWidget* m_list = nullptr;
    QPushButton* m_undo = nullptr;
    QPushButton* m_redo = nullptr;
    QLabel* m_summary = nullptr;
    bool m_busy = false;
    bool m_populating = false;
};

} // namespace chunkdaddy
