#include "app/SpawnMarkerDialog.h"

#include <QCheckBox>
#include <QDialogButtonBox>
#include <QDoubleSpinBox>
#include <QGroupBox>
#include <QHBoxLayout>
#include <QLabel>
#include <QListWidget>
#include <QPushButton>
#include <QTextBrowser>
#include <QVBoxLayout>

namespace chunkdaddy {
namespace {

QDoubleSpinBox* makeAxisBox(QWidget* parent) {
    auto* box = new QDoubleSpinBox(parent);
    box->setDecimals(2);
    box->setSingleStep(0.5);
    box->setRange(-100000.0, 100000.0);
    return box;
}

} // namespace

SpawnMarkerDialog::SpawnMarkerDialog(const QVector<TemplateInfo>& templates, QWidget* parent)
    : QDialog(parent), m_templates(templates) {
    setWindowTitle(tr("Template spawn markers"));
    setModal(true);

    m_list = new QListWidget(this);
    m_details = new QLabel(this);
    m_details->setWordWrap(true);
    m_details->setTextFormat(Qt::PlainText);

    m_x1 = makeAxisBox(this);
    m_y1 = makeAxisBox(this);
    m_z1 = makeAxisBox(this);
    m_x2 = makeAxisBox(this);
    m_y2 = makeAxisBox(this);
    m_z2 = makeAxisBox(this);

    auto* group1 = new QGroupBox(tr("Spawn 1 (local, player feet)"), this);
    auto* row1 = new QHBoxLayout(group1);
    row1->addWidget(new QLabel(tr("X"), this));
    row1->addWidget(m_x1);
    row1->addWidget(new QLabel(tr("Y"), this));
    row1->addWidget(m_y1);
    row1->addWidget(new QLabel(tr("Z"), this));
    row1->addWidget(m_z1);

    auto* group2 = new QGroupBox(tr("Spawn 2 (local, player feet)"), this);
    auto* row2 = new QHBoxLayout(group2);
    row2->addWidget(new QLabel(tr("X"), this));
    row2->addWidget(m_x2);
    row2->addWidget(new QLabel(tr("Y"), this));
    row2->addWidget(m_y2);
    row2->addWidget(new QLabel(tr("Z"), this));
    row2->addWidget(m_z2);

    m_confirm = new QCheckBox(tr("These positions are confirmed for this template's current file"), this);
    m_warnings = new QTextBrowser(this);
    m_warnings->setMinimumHeight(120);

    auto* buttons = new QDialogButtonBox(this);
    auto* suggest = buttons->addButton(tr("Suggest opposite ends"), QDialogButtonBox::ActionRole);
    auto* validate = buttons->addButton(tr("Validate"), QDialogButtonBox::ActionRole);
    buttons->addButton(tr("Save"), QDialogButtonBox::AcceptRole);
    buttons->addButton(QDialogButtonBox::Close);

    connect(suggest, &QPushButton::clicked, this, &SpawnMarkerDialog::suggestOppositeEnds);
    connect(validate, &QPushButton::clicked, this, [this] {
        emit validationRequested(selectedTemplateId(), spawnPoint1(), spawnPoint2(), false);
    });
    connect(buttons, &QDialogButtonBox::accepted, this, [this] {
        emit validationRequested(selectedTemplateId(), spawnPoint1(), spawnPoint2(),
                                 m_confirm->isChecked());
    });
    connect(buttons, &QDialogButtonBox::rejected, this, &QDialog::reject);
    connect(m_list, &QListWidget::currentRowChanged, this, &SpawnMarkerDialog::onTemplateChanged);

    auto* left = new QVBoxLayout;
    left->addWidget(new QLabel(tr("Templates"), this));
    left->addWidget(m_list, 1);

    auto* right = new QVBoxLayout;
    right->addWidget(m_details);
    right->addWidget(group1);
    right->addWidget(group2);
    right->addWidget(m_confirm);
    right->addWidget(new QLabel(tr("Checks"), this));
    right->addWidget(m_warnings, 1);

    auto* columns = new QHBoxLayout;
    columns->addLayout(left, 1);
    columns->addLayout(right, 2);

    auto* layout = new QVBoxLayout(this);
    layout->addWidget(new QLabel(
        tr("These schematics carry no spawn metadata. WorldEdit's origin and the schematic offset "
           "describe where the copy came from, not where a player should stand, so the two duel "
           "positions are authored here once per template and saved against the file's hash."),
        this));
    static_cast<QLabel*>(layout->itemAt(0)->widget())->setWordWrap(true);
    layout->addLayout(columns, 1);
    layout->addWidget(buttons);

    setTemplates(templates);
    resize(900, 620);
}

void SpawnMarkerDialog::setTemplates(const QVector<TemplateInfo>& templates) {
    const QString previous = selectedTemplateId();
    m_templates = templates;

    QSignalBlocker blocker(m_list);
    m_list->clear();
    for (const TemplateInfo& info : m_templates) {
        auto* item = new QListWidgetItem(
            info.spawnsConfirmed ? tr("%1  ✓").arg(info.slug) : info.slug, m_list);
        item->setData(Qt::UserRole, info.templateId);
        if (!info.spawnsConfirmed) {
            item->setForeground(QColor(200, 140, 40));
        }
    }
    int row = 0;
    for (int i = 0; i < m_templates.size(); ++i) {
        if (m_templates.at(i).templateId == previous) {
            row = i;
            break;
        }
    }
    if (m_list->count() > 0) {
        m_list->setCurrentRow(row);
    }
    onTemplateChanged();
}

const TemplateInfo* SpawnMarkerDialog::currentTemplate() const {
    const int row = m_list->currentRow();
    if (row < 0 || row >= m_templates.size()) {
        return nullptr;
    }
    return &m_templates.at(row);
}

void SpawnMarkerDialog::onTemplateChanged() {
    const TemplateInfo* info = currentTemplate();
    if (!info) {
        m_details->clear();
        return;
    }
    applyTemplateRanges(*info);

    QString details = tr("%1\n%2\nSize %3 x %4 x %5 blocks, %6 x %7 chunks.")
                          .arg(info->sourceFile)
                          .arg(info->sourceSha256.left(16) + QStringLiteral("…"))
                          .arg(info->sizeX).arg(info->sizeY).arg(info->sizeZ)
                          .arg(info->footprintChunksX).arg(info->footprintChunksZ);
    if (!info->hasWorldEditOrigin) {
        details += tr("\n\nThis file carries no WorldEdit origin, so its original world position "
                      "cannot be reconstructed. Enter positions in schematic-local coordinates.");
    }
    m_details->setText(details);

    QSignalBlocker b1(m_x1), b2(m_y1), b3(m_z1), b4(m_x2), b5(m_y2), b6(m_z2), b7(m_confirm);
    if (info->spawnPoint1 && info->spawnPoint1->size() == 3) {
        m_x1->setValue(info->spawnPoint1->at(0));
        m_y1->setValue(info->spawnPoint1->at(1));
        m_z1->setValue(info->spawnPoint1->at(2));
    }
    if (info->spawnPoint2 && info->spawnPoint2->size() == 3) {
        m_x2->setValue(info->spawnPoint2->at(0));
        m_y2->setValue(info->spawnPoint2->at(1));
        m_z2->setValue(info->spawnPoint2->at(2));
    }
    m_confirm->setChecked(info->spawnsConfirmed);
    m_warnings->clear();
}

void SpawnMarkerDialog::applyTemplateRanges(const TemplateInfo& info) {
    m_x1->setRange(0.0, info.sizeX);
    m_x2->setRange(0.0, info.sizeX);
    m_y1->setRange(0.0, info.sizeY);
    m_y2->setRange(0.0, info.sizeY);
    m_z1->setRange(0.0, info.sizeZ);
    m_z2->setRange(0.0, info.sizeZ);
}

void SpawnMarkerDialog::suggestOppositeEnds() {
    const TemplateInfo* info = currentTemplate();
    if (!info) {
        return;
    }
    // A convenience only. Geometric extremes are frequently walls, scenery or roofs, so
    // the suggestion is never treated as confirmed.
    const double centreX = info->sizeX / 2.0;
    m_x1->setValue(centreX);
    m_z1->setValue(info->sizeZ * 0.15);
    m_x2->setValue(centreX);
    m_z2->setValue(info->sizeZ * 0.85);
    m_confirm->setChecked(false);
    m_warnings->setHtml(
        tr("<b>Unconfirmed suggestion.</b> These are geometric positions along the Z axis at the "
           "centre of X; they have not been checked against the build. Set the height slice to the "
           "playable floor, adjust the values, and validate before confirming."));
}

QString SpawnMarkerDialog::selectedTemplateId() const {
    QListWidgetItem* item = m_list->currentItem();
    return item ? item->data(Qt::UserRole).toString() : QString();
}

QVector<double> SpawnMarkerDialog::spawnPoint1() const {
    return {m_x1->value(), m_y1->value(), m_z1->value()};
}

QVector<double> SpawnMarkerDialog::spawnPoint2() const {
    return {m_x2->value(), m_y2->value(), m_z2->value()};
}

bool SpawnMarkerDialog::confirmed() const {
    return m_confirm->isChecked();
}

void SpawnMarkerDialog::showWarnings(const QStringList& warnings) {
    if (warnings.isEmpty()) {
        m_warnings->setHtml(tr("<b style='color:#40a060'>No problems found.</b><br>"
                               "Bounds, support, clearance and obstruction all look reasonable. "
                               "These checks treat blocks as full cubes, so a partial block still "
                               "needs your eye."));
        return;
    }
    QStringList lines;
    lines << tr("<b style='color:#c07030'>Check these before confirming:</b>");
    for (const QString& warning : warnings) {
        lines << QStringLiteral("&bull; ") + warning.toHtmlEscaped();
    }
    m_warnings->setHtml(lines.join(QStringLiteral("<br>")));
}

} // namespace chunkdaddy
