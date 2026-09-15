#include "app/TemplatePanel.h"

#include <QHeaderView>
#include <QLineEdit>
#include <QTreeWidget>
#include <QVBoxLayout>

namespace chunkdaddy {

TemplatePanel::TemplatePanel(QWidget* parent) : QWidget(parent) {
    m_filter = new QLineEdit(this);
    m_filter->setPlaceholderText(tr("Filter by map name…"));
    m_filter->setClearButtonEnabled(true);

    m_tree = new QTreeWidget(this);
    m_tree->setColumnCount(2);
    m_tree->setHeaderLabels({tr("Template / arena"), tr("Status")});
    m_tree->header()->setSectionResizeMode(0, QHeaderView::Stretch);
    m_tree->header()->setSectionResizeMode(1, QHeaderView::ResizeToContents);

    connect(m_filter, &QLineEdit::textChanged, this, [this] { rebuild(); });
    connect(m_tree, &QTreeWidget::itemSelectionChanged, this, [this] {
        QTreeWidgetItem* item = m_tree->currentItem();
        if (!item) {
            return;
        }
        const QString arenaId = item->data(0, Qt::UserRole + 1).toString();
        if (!arenaId.isEmpty()) {
            emit arenaSelected(arenaId);
            return;
        }
        const QString templateId = item->data(0, Qt::UserRole).toString();
        if (!templateId.isEmpty()) {
            emit templateSelected(templateId);
        }
    });
    connect(m_tree, &QTreeWidget::itemDoubleClicked, this, [this](QTreeWidgetItem* item, int) {
        const QString templateId = item->data(0, Qt::UserRole).toString();
        if (!templateId.isEmpty() && item->data(0, Qt::UserRole + 1).toString().isEmpty()) {
            emit editSpawnsRequested(templateId);
        }
    });

    auto* layout = new QVBoxLayout(this);
    layout->setContentsMargins(4, 4, 4, 4);
    layout->addWidget(m_filter);
    layout->addWidget(m_tree, 1);
}

void TemplatePanel::setDocument(Document* document) {
    if (m_document) {
        disconnect(m_document, nullptr, this, nullptr);
    }
    m_document = document;
    if (m_document) {
        connect(m_document, &Document::stateChanged, this, [this] { rebuild(); });
    }
    rebuild();
}

void TemplatePanel::rebuild() {
    m_tree->clear();
    if (!m_document) {
        return;
    }
    const QString filter = m_filter->text().trimmed().toLower();

    for (const TemplateInfo& info : m_document->templates()) {
        if (!filter.isEmpty() && !info.slug.contains(filter) && !info.sourceFile.toLower().contains(filter)) {
            continue;
        }
        auto* root = new QTreeWidgetItem(m_tree);
        root->setText(0, info.slug);
        root->setText(1, info.statusText());
        root->setData(0, Qt::UserRole, info.templateId);
        if (!info.spawnsReady() || info.blockingIssueCount > 0) {
            root->setForeground(1, QColor(200, 140, 40));
        }
        if (!info.issueLines.isEmpty()) {
            root->setToolTip(1, info.issueLines.join(QLatin1Char('\n')));
        }

        for (const ArenaInfo& arena : m_document->arenas()) {
            if (arena.templateId != info.templateId) {
                continue;
            }
            auto* child = new QTreeWidgetItem(root);
            child->setText(0, arena.exportId);
            child->setText(1, arena.needsRevalidation
                                  ? tr("needs revalidation")
                                  : tr("chunk %1,%2").arg(arena.chunkBounds.minX())
                                        .arg(arena.chunkBounds.minZ()));
            child->setData(0, Qt::UserRole, info.templateId);
            child->setData(0, Qt::UserRole + 1, arena.id);
            if (arena.needsRevalidation) {
                child->setForeground(1, QColor(210, 80, 80));
            }
        }
        // Four hundred and fifty children per template would be unreadable expanded.
        root->setExpanded(m_document->arenas().size() <= 40);
    }
}

} // namespace chunkdaddy
