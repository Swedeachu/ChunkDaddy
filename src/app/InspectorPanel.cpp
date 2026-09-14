#include "app/InspectorPanel.h"

#include <QFormLayout>
#include <QGroupBox>
#include <QHBoxLayout>
#include <QLabel>
#include <QPushButton>
#include <QSpinBox>
#include <QVBoxLayout>

namespace chunkdaddy {
namespace {

QSpinBox* makeChunkBox(QWidget* parent) {
    auto* box = new QSpinBox(parent);
    box->setRange(-1'800'000, 1'800'000);
    return box;
}

} // namespace

InspectorPanel::InspectorPanel(QWidget* parent) : QWidget(parent) {
    m_selectionSummary = new QLabel(this);
    m_selectionSummary->setWordWrap(true);

    m_selMinX = makeChunkBox(this);
    m_selMinZ = makeChunkBox(this);
    m_selMaxX = makeChunkBox(this);
    m_selMaxZ = makeChunkBox(this);

    auto* selectionBox = new QGroupBox(tr("Selection (inclusive chunks)"), this);
    auto* selectionForm = new QFormLayout(selectionBox);
    auto* selMin = new QHBoxLayout;
    selMin->addWidget(m_selMinX);
    selMin->addWidget(m_selMinZ);
    auto* selMax = new QHBoxLayout;
    selMax->addWidget(m_selMaxX);
    selMax->addWidget(m_selMaxZ);
    selectionForm->addRow(tr("Minimum X / Z"), selMin);
    selectionForm->addRow(tr("Maximum X / Z"), selMax);
    selectionForm->addRow(m_selectionSummary);

    auto* applySelection = new QPushButton(tr("Apply to selection"), this);
    selectionForm->addRow(applySelection);
    connect(applySelection, &QPushButton::clicked, this, [this] {
        emit selectionBoundsEdited(ChunkRect::fromCorners(m_selMinX->value(), m_selMinZ->value(),
                                                          m_selMaxX->value(), m_selMaxZ->value()));
    });

    m_exportMinX = makeChunkBox(this);
    m_exportMinZ = makeChunkBox(this);
    m_exportMaxX = makeChunkBox(this);
    m_exportMaxZ = makeChunkBox(this);
    m_border = new QSpinBox(this);
    m_border->setRange(0, 1024);
    m_border->setSuffix(tr(" chunks"));
    m_exportSummary = new QLabel(this);
    m_exportSummary->setWordWrap(true);

    auto* exportBox = new QGroupBox(tr("Export rectangle"), this);
    auto* exportForm = new QFormLayout(exportBox);
    auto* expMin = new QHBoxLayout;
    expMin->addWidget(m_exportMinX);
    expMin->addWidget(m_exportMinZ);
    auto* expMax = new QHBoxLayout;
    expMax->addWidget(m_exportMaxX);
    expMax->addWidget(m_exportMaxZ);
    exportForm->addRow(tr("Minimum X / Z"), expMin);
    exportForm->addRow(tr("Maximum X / Z"), expMax);
    exportForm->addRow(tr("Border"), m_border);
    exportForm->addRow(m_exportSummary);

    auto* applyExport = new QPushButton(tr("Set explicitly"), this);
    auto* resetExport = new QPushButton(tr("Follow content"), this);
    auto* exportButtons = new QHBoxLayout;
    exportButtons->addWidget(applyExport);
    exportButtons->addWidget(resetExport);
    exportForm->addRow(exportButtons);

    connect(applyExport, &QPushButton::clicked, this, [this] {
        emit exportRectangleEdited(
            ChunkRect::fromCorners(m_exportMinX->value(), m_exportMinZ->value(),
                                   m_exportMaxX->value(), m_exportMaxZ->value()),
            m_border->value());
    });
    connect(resetExport, &QPushButton::clicked, this,
            [this] { emit exportRectangleReset(m_border->value()); });

    m_profileSummary = new QLabel(this);
    m_profileSummary->setWordWrap(true);
    auto* profileBox = new QGroupBox(tr("Target profile"), this);
    auto* profileLayout = new QVBoxLayout(profileBox);
    profileLayout->addWidget(m_profileSummary);

    auto* layout = new QVBoxLayout(this);
    layout->setContentsMargins(4, 4, 4, 4);
    layout->addWidget(selectionBox);
    layout->addWidget(exportBox);
    layout->addWidget(profileBox);
    layout->addStretch();

    refresh();
}

void InspectorPanel::setDocument(Document* document) {
    if (m_document) {
        disconnect(m_document, nullptr, this, nullptr);
    }
    m_document = document;
    if (m_document) {
        connect(m_document, &Document::stateChanged, this, [this] { refresh(); });
        connect(m_document, &Document::selectionChanged, this, [this] { refresh(); });
    }
    refresh();
}

void InspectorPanel::setProfile(const ProfileInfo& profile) {
    m_profile = profile;
    refresh();
}

void InspectorPanel::refresh() {
    if (m_updating) {
        return;
    }
    m_updating = true;

    const bool hasDocument = m_document != nullptr;
    if (hasDocument && !m_document->selection().isEmpty()) {
        const ChunkRect bounds = m_document->selection().bounds();
        m_selMinX->setValue(bounds.minX());
        m_selMinZ->setValue(bounds.minZ());
        m_selMaxX->setValue(bounds.maxX());
        m_selMaxZ->setValue(bounds.maxZ());
        m_selectionSummary->setText(
            tr("%1 chunk column(s) selected.\nBlocks X %2 to %3, Z %4 to %5.")
                .arg(m_document->selection().columnCount())
                .arg(bounds.minBlock().x).arg(bounds.maxBlock().x)
                .arg(bounds.minBlock().z).arg(bounds.maxBlock().z));
    } else {
        m_selectionSummary->setText(tr("Nothing selected. Drag in the viewport, or type bounds "
                                       "above and apply them."));
    }

    if (hasDocument) {
        if (const auto rectangle = m_document->exportRectangle()) {
            m_exportMinX->setValue(rectangle->minX());
            m_exportMinZ->setValue(rectangle->minZ());
            m_exportMaxX->setValue(rectangle->maxX());
            m_exportMaxZ->setValue(rectangle->maxZ());
            m_exportSummary->setText(
                tr("%1 column(s) will be generated.\n%2")
                    .arg(rectangle->columnCount())
                    .arg(m_document->hasExplicitExportRectangle()
                             ? tr("Set explicitly; it does not follow content.")
                             : tr("Following the bounding rectangle of all content, plus the border.")));
        } else {
            m_exportSummary->setText(tr("This world has no content yet, so there is no rectangle."));
        }
        m_border->setValue(m_document->exportBorderChunks());
    }

    if (m_profile.id.isEmpty()) {
        m_profileSummary->setText(tr("No profile selected."));
    } else {
        m_profileSummary->setText(tr("%1 (Bedrock %2)\nBuild range Y %3 to %4.\n\n%5")
                                      .arg(m_profile.displayName, m_profile.version)
                                      .arg(m_profile.minBlockY)
                                      .arg(m_profile.maxBlockY)
                                      .arg(m_profile.verificationSummary));
    }

    m_updating = false;
}

} // namespace chunkdaddy
