#pragma once

#include "document/Document.h"

#include <QWidget>

class QLineEdit;
class QTreeWidget;

namespace chunkdaddy {

/// Left dock: imported templates and the arena instances placed from them.
class TemplatePanel : public QWidget {
    Q_OBJECT

public:
    explicit TemplatePanel(QWidget* parent = nullptr);

    void setDocument(Document* document);

signals:
    void arenaSelected(const QString& arenaId);
    void templateSelected(const QString& templateId);
    void editSpawnsRequested(const QString& templateId);

private:
    void rebuild();

    Document* m_document = nullptr;
    QLineEdit* m_filter = nullptr;
    QTreeWidget* m_tree = nullptr;
};

} // namespace chunkdaddy
