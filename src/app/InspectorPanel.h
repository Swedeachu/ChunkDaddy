#pragma once

#include "document/Document.h"
#include "document/Workspace.h"

#include <QWidget>

class QLabel;
class QPushButton;
class QSpinBox;

namespace chunkdaddy {

/// Right dock: selection bounds, destination coordinates, export rectangle and the
/// target profile's verification state.
class InspectorPanel : public QWidget {
    Q_OBJECT

public:
    explicit InspectorPanel(QWidget* parent = nullptr);

    void setDocument(Document* document);
    void setProfile(const ProfileInfo& profile);

signals:
    void selectionBoundsEdited(const ChunkRect& rect);
    void exportRectangleEdited(const ChunkRect& rect, int borderChunks);
    void exportRectangleReset(int borderChunks);

private:
    void refresh();

    Document* m_document = nullptr;
    ProfileInfo m_profile;

    QLabel* m_selectionSummary = nullptr;
    QSpinBox* m_selMinX = nullptr;
    QSpinBox* m_selMinZ = nullptr;
    QSpinBox* m_selMaxX = nullptr;
    QSpinBox* m_selMaxZ = nullptr;
    QSpinBox* m_exportMinX = nullptr;
    QSpinBox* m_exportMinZ = nullptr;
    QSpinBox* m_exportMaxX = nullptr;
    QSpinBox* m_exportMaxZ = nullptr;
    QSpinBox* m_border = nullptr;
    QLabel* m_exportSummary = nullptr;
    QLabel* m_profileSummary = nullptr;
    bool m_updating = false;
};

} // namespace chunkdaddy
