#include "app/HistoryPanel.h"

#include "document/Document.h"
#include "document/History.h"

#include <QFont>
#include <QHBoxLayout>
#include <QLabel>
#include <QListWidget>
#include <QPushButton>
#include <QVBoxLayout>

namespace chunkdaddy {

HistoryPanel::HistoryPanel(QWidget* parent) : QWidget(parent) {
    m_list = new QListWidget(this);
    m_list->setAlternatingRowColors(true);
    m_list->setSelectionMode(QAbstractItemView::SingleSelection);
    m_list->setToolTip(tr("Click a step to take the world back to how it looked at that point."));

    m_undo = new QPushButton(tr("Undo"), this);
    m_redo = new QPushButton(tr("Redo"), this);
    m_undo->setShortcutEnabled(false);
    m_redo->setShortcutEnabled(false);

    m_summary = new QLabel(this);
    m_summary->setWordWrap(true);

    auto* buttons = new QHBoxLayout;
    buttons->setContentsMargins(0, 0, 0, 0);
    buttons->addWidget(m_undo);
    buttons->addWidget(m_redo);
    buttons->addStretch(1);

    auto* layout = new QVBoxLayout(this);
    layout->setContentsMargins(6, 6, 6, 6);
    layout->addLayout(buttons);
    layout->addWidget(m_list, 1);
    layout->addWidget(m_summary);

    connect(m_undo, &QPushButton::clicked, this, &HistoryPanel::undoRequested);
    connect(m_redo, &QPushButton::clicked, this, &HistoryPanel::redoRequested);
    connect(m_list, &QListWidget::itemClicked, this, [this](QListWidgetItem* item) {
        onRowActivated(m_list->row(item));
    });
    refresh();
}

void HistoryPanel::setSources(History* history, Document* document) {
    if (m_history != history) {
        if (m_history) disconnect(m_history, nullptr, this, nullptr);
        m_history = history;
        if (m_history) connect(m_history, &History::changed, this, &HistoryPanel::refresh);
    }
    m_document = document;
    refresh();
}

void HistoryPanel::setBusy(bool busy) {
    m_busy = busy;
    refresh();
}

void HistoryPanel::refresh() {
    // Repopulating resets the current row, which would otherwise look like the user
    // clicking a step and set off a jump.
    m_populating = true;
    m_list->clear();

    const bool ready = m_history && m_document;
    const QVector<HistoryStep> steps = ready ? m_history->steps(m_document->documentId())
                                             : QVector<HistoryStep>{};
    const int cursor = ready ? m_history->cursor(m_document->documentId()) : 0;

    auto* base = new QListWidgetItem(tr("Opened"), m_list);
    base->setForeground(palette().placeholderText());

    for (int i = 0; i < steps.size(); ++i) {
        const HistoryStep& step = steps.at(i);
        auto* item = new QListWidgetItem(
            tr("%1.  %2   (r%3)").arg(i + 1).arg(step.description).arg(step.revision), m_list);
        if (i >= cursor) {
            // Undone, but still reachable: shown struck through rather than hidden, so
            // the redo tail is visible until a new edit actually discards it.
            QFont font = item->font();
            font.setStrikeOut(true);
            item->setFont(font);
            item->setForeground(palette().placeholderText());
        }
    }
    if (cursor >= 0 && cursor < m_list->count()) {
        m_list->setCurrentRow(cursor);
    }
    m_populating = false;

    const bool canUndo = ready && m_document->canUndo() && !m_busy;
    const bool canRedo = ready && m_document->canRedo() && !m_busy;
    m_undo->setEnabled(canUndo);
    m_redo->setEnabled(canRedo);
    m_list->setEnabled(ready && !m_busy);

    if (!ready) {
        m_summary->setText(tr("No world open."));
    } else if (steps.isEmpty()) {
        m_summary->setText(tr("No edits yet."));
    } else if (cursor == 0) {
        m_summary->setText(tr("At the opened state; %n edit(s) can be redone.", nullptr, steps.size()));
    } else {
        m_summary->setText(tr("At step %1 of %2: %3")
                               .arg(cursor)
                               .arg(steps.size())
                               .arg(steps.at(cursor - 1).description));
    }
}

void HistoryPanel::onRowActivated(int row) {
    if (m_populating || m_busy || !m_history || !m_document || row < 0) {
        return;
    }
    // Row 0 is the opened state, row n is "n steps applied".
    if (row == m_history->cursor(m_document->documentId())) {
        return;
    }
    emit jumpRequested(row);
}

} // namespace chunkdaddy
