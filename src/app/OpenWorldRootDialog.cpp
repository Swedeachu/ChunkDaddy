#include "app/OpenWorldRootDialog.h"

#include <QDialogButtonBox>
#include <QHeaderView>
#include <QJsonObject>
#include <QLabel>
#include <QTreeWidget>
#include <QVBoxLayout>

namespace chunkdaddy {

OpenWorldRootDialog::OpenWorldRootDialog(const QJsonArray& worlds, QWidget* parent)
    : QDialog(parent) {
    setWindowTitle(tr("Choose a world"));
    setModal(true);

    auto* description = new QLabel(
        tr("This container holds more than one world. Opening a world puts it in its own tab; "
           "it is not merged into the active world."),
        this);
    description->setWordWrap(true);

    m_tree = new QTreeWidget(this);
    m_tree->setColumnCount(4);
    m_tree->setHeaderLabels({tr("Name"), tr("Edition"), tr("Version"), tr("Path in container")});
    m_tree->setRootIsDecorated(false);
    m_tree->setSelectionMode(QAbstractItemView::SingleSelection);

    for (const QJsonValue& value : worlds) {
        const QJsonObject world = value.toObject();
        auto* item = new QTreeWidgetItem(m_tree);
        item->setText(0, world.value(QStringLiteral("name")).toString());
        item->setText(1, world.value(QStringLiteral("edition")).toString());
        item->setText(2, world.value(QStringLiteral("versionDescription")).toString());
        const QString relative = world.value(QStringLiteral("relativePath")).toString();
        item->setText(3, relative.isEmpty() ? tr("(container root)") : relative);
        item->setData(0, Qt::UserRole, world.value(QStringLiteral("directory")).toString());
        item->setData(1, Qt::UserRole, world.value(QStringLiteral("edition")).toString());
    }
    m_tree->header()->setSectionResizeMode(QHeaderView::ResizeToContents);
    if (m_tree->topLevelItemCount() > 0) {
        m_tree->setCurrentItem(m_tree->topLevelItem(0));
    }

    auto* buttons = new QDialogButtonBox(QDialogButtonBox::Open | QDialogButtonBox::Cancel, this);
    connect(buttons, &QDialogButtonBox::accepted, this, &QDialog::accept);
    connect(buttons, &QDialogButtonBox::rejected, this, &QDialog::reject);
    connect(m_tree, &QTreeWidget::itemDoubleClicked, this, &QDialog::accept);

    auto* layout = new QVBoxLayout(this);
    layout->addWidget(description);
    layout->addWidget(m_tree, 1);
    layout->addWidget(buttons);
    resize(760, 420);
}

OpenWorldRootDialog::Choice OpenWorldRootDialog::choice() const {
    Choice result;
    if (QTreeWidgetItem* item = m_tree->currentItem()) {
        result.directory = item->data(0, Qt::UserRole).toString();
        result.edition = item->data(1, Qt::UserRole).toString();
        result.name = item->text(0);
    }
    return result;
}

} // namespace chunkdaddy
