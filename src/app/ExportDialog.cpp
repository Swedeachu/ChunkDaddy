#include "app/ExportDialog.h"

#include "app/Settings.h"

#include <QCheckBox>
#include <QComboBox>
#include <QDialogButtonBox>
#include <QDir>
#include <QFileDialog>
#include <QFileInfo>
#include <QFormLayout>
#include <QHBoxLayout>
#include <QJsonArray>
#include <QLabel>
#include <QLineEdit>
#include <QPushButton>
#include <QTextBrowser>
#include <QVBoxLayout>

namespace chunkdaddy {
namespace {

QString formatBytes(qint64 bytes) {
    if (bytes < 1024) return QStringLiteral("%1 B").arg(bytes);
    if (bytes < 1024LL * 1024) return QStringLiteral("%1 KiB").arg(bytes / 1024);
    if (bytes < 1024LL * 1024 * 1024) return QStringLiteral("%1 MiB").arg(bytes / (1024 * 1024));
    return QStringLiteral("%1 GiB").arg(bytes / (1024.0 * 1024 * 1024), 0, 'f', 1);
}

QString sanitizeFileName(const QString& name) {
    QString cleaned = name;
    cleaned.replace(QRegularExpression(QStringLiteral("[\\\\/:*?\"<>|]")), QStringLiteral("_"));
    cleaned = cleaned.trimmed();
    return cleaned.isEmpty() ? QStringLiteral("world") : cleaned;
}

} // namespace

ExportDialog::ExportDialog(const Document* document, const QString& profileSummary,
                           const QJsonObject& validation, QWidget* parent)
    : QDialog(parent), m_document(document), m_profileSummary(profileSummary), m_validation(validation) {
    setWindowTitle(tr("Export world"));
    setModal(true);

    m_worldName = new QLineEdit(document->name(), this);

    m_mode = new QComboBox(this);
    m_mode->addItem(tr(".mcworld archive (vanilla import)"), QStringLiteral("MCWORLD"));
    m_mode->addItem(tr(".zip archive (same layout, for deployment)"), QStringLiteral("ZIP"));
    m_mode->addItem(tr("World folder (BDS and Tungsten)"), QStringLiteral("DIRECTORY"));

    m_destination = new QLineEdit(this);
    auto* browseButton = new QPushButton(tr("Browse…"), this);
    connect(browseButton, &QPushButton::clicked, this, &ExportDialog::browse);

    m_numberMode = new QComboBox(this);
    m_numberMode->addItem(tr("Exact (keeps half-block offsets)"), QStringLiteral("EXACT"));
    m_numberMode->addItem(tr("Whole numbers only"), QStringLiteral("INTEGER"));

    m_arenaPreset = new QCheckBox(tr("Apply the arena world preset"), this);
    m_arenaPreset->setChecked(true);
    m_arenaPreset->setToolTip(
        tr("Disables random ticks, mob spawning, fire spread, weather and the daylight cycle in "
           "the exported level settings. This is not a freeze: scheduled ticks, gravity, fluid "
           "flow and player-triggered neighbour updates still happen in game."));

    m_useSystemDownloads = new QCheckBox(tr("Use the system Downloads folder"), this);
    m_useSystemDownloads->setChecked(Settings::exportDirectoryIsSystemDefault());
    connect(m_useSystemDownloads, &QCheckBox::toggled, this, [this](bool useDefault) {
        if (useDefault) {
            const QString base = Settings::downloadsDirectory();
            m_destination->setText(QDir(base).filePath(sanitizeFileName(worldName()) + currentExtension()));
        }
    });

    m_summary = new QTextBrowser(this);
    m_summary->setMinimumHeight(220);

    auto* form = new QFormLayout;
    form->addRow(tr("World name"), m_worldName);
    form->addRow(tr("Output"), m_mode);

    auto* destinationRow = new QHBoxLayout;
    destinationRow->addWidget(m_destination, 1);
    destinationRow->addWidget(browseButton);
    form->addRow(tr("Save to"), destinationRow);
    form->addRow(QString(), m_useSystemDownloads);
    form->addRow(tr("Spawn coordinates"), m_numberMode);
    form->addRow(QString(), m_arenaPreset);

    auto* buttons = new QDialogButtonBox(this);
    m_exportButton = buttons->addButton(tr("Export"), QDialogButtonBox::AcceptRole);
    buttons->addButton(QDialogButtonBox::Cancel);
    connect(buttons, &QDialogButtonBox::accepted, this, &QDialog::accept);
    connect(buttons, &QDialogButtonBox::rejected, this, &QDialog::reject);

    connect(m_mode, &QComboBox::currentIndexChanged, this, [this] {
        const QFileInfo info(m_destination->text());
        const QString directory = info.absolutePath();
        m_destination->setText(QDir(directory).filePath(sanitizeFileName(worldName()) + currentExtension()));
        updateSummary();
    });
    connect(m_worldName, &QLineEdit::textChanged, this, [this] {
        const QFileInfo info(m_destination->text());
        m_destination->setText(
            QDir(info.absolutePath()).filePath(sanitizeFileName(worldName()) + currentExtension()));
        updateSummary();
    });
    connect(m_destination, &QLineEdit::textChanged, this, &ExportDialog::updateSummary);

    auto* layout = new QVBoxLayout(this);
    layout->addLayout(form);
    layout->addWidget(m_summary, 1);
    layout->addWidget(buttons);

    const QString base = Settings::lastExportDirectory();
    m_destination->setText(QDir(base).filePath(sanitizeFileName(worldName()) + currentExtension()));
    updateSummary();
    resize(760, 640);
}

QString ExportDialog::currentExtension() const {
    const QString mode = m_mode->currentData().toString();
    if (mode == QStringLiteral("MCWORLD")) return QStringLiteral(".mcworld");
    if (mode == QStringLiteral("ZIP")) return QStringLiteral(".zip");
    return QString();
}

void ExportDialog::browse() {
    const QString mode = m_mode->currentData().toString();
    QString chosen;
    if (mode == QStringLiteral("DIRECTORY")) {
        chosen = QFileDialog::getExistingDirectory(this, tr("Choose an output folder"),
                                                   QFileInfo(m_destination->text()).absolutePath());
        if (!chosen.isEmpty()) {
            chosen = QDir(chosen).filePath(sanitizeFileName(worldName()));
        }
    } else {
        const QString filter = mode == QStringLiteral("MCWORLD")
                                   ? tr("Bedrock world (*.mcworld)")
                                   : tr("Zip archive (*.zip)");
        chosen = QFileDialog::getSaveFileName(this, tr("Export world"), m_destination->text(), filter);
    }
    if (chosen.isEmpty()) {
        return;
    }
    m_destination->setText(chosen);
    m_useSystemDownloads->setChecked(false);
    Settings::setLastExportDirectory(QFileInfo(chosen).absolutePath());
}

void ExportDialog::updateSummary() {
    QStringList lines;

    const QJsonArray problems = m_validation.value(QStringLiteral("problems")).toArray();
    if (!problems.isEmpty()) {
        lines << tr("<b style='color:#c04040'>%1 problem(s) block this export:</b>").arg(problems.size());
        int shown = 0;
        for (const QJsonValue& value : problems) {
            if (shown++ >= 12) {
                lines << tr("&bull; …and %1 more.").arg(problems.size() - 12);
                break;
            }
            lines << QStringLiteral("&bull; ") + value.toString().toHtmlEscaped();
        }
        lines << QString();
    }

    if (m_validation.contains(QStringLiteral("exportRectangle"))) {
        const QJsonObject rect = m_validation.value(QStringLiteral("exportRectangle")).toObject();
        lines << tr("Export rectangle: chunks %1,%2 to %3,%4 (%5 x %6).")
                     .arg(rect.value(QStringLiteral("minChunkX")).toInt())
                     .arg(rect.value(QStringLiteral("minChunkZ")).toInt())
                     .arg(rect.value(QStringLiteral("maxChunkX")).toInt())
                     .arg(rect.value(QStringLiteral("maxChunkZ")).toInt())
                     .arg(rect.value(QStringLiteral("widthChunks")).toInt())
                     .arg(rect.value(QStringLiteral("lengthChunks")).toInt());
        const qint64 columns =
            static_cast<qint64>(m_validation.value(QStringLiteral("columnCount")).toDouble());
        const qint64 bytes =
            static_cast<qint64>(m_validation.value(QStringLiteral("estimatedBytes")).toDouble());
        lines << tr("Every one of these %1 column(s) is written, including the gaps between arenas "
                    "and the unused cells of the last grid row.")
                     .arg(columns);
        lines << tr("Rough size on disk: %1.").arg(formatBytes(bytes));
        if (columns > 2'000'000) {
            lines << tr("<b style='color:#c07030'>That is a very large rectangle.</b> If your "
                        "content is thousands of blocks apart, the whole space between it is "
                        "included.");
        }
    }

    const int arenas = m_validation.value(QStringLiteral("arenaCount")).toInt();
    lines << tr("Arenas: %1, spawn positions: %2.")
                 .arg(arenas)
                 .arg(m_validation.value(QStringLiteral("spawnPointCount")).toInt());

    const QString name = sanitizeFileName(worldName());
    lines << QString();
    lines << tr("Files written:");
    lines << tr("&bull; the world, with <code>level.dat</code>, <code>levelname.txt</code> and "
                "<code>db/</code> at its root");
    lines << tr("&bull; <code>arenas.json</code> at the world root — the authoritative copy");
    lines << tr("&bull; <code>chunkdaddy-manifest.json</code> and a conversion report");
    lines << tr("&bull; <code>%1.arenas.json</code> next to the output, byte for byte identical")
                 .arg(name);

    lines << QString();
    lines << tr("Target profile: %1").arg(m_profileSummary.toHtmlEscaped());

    m_summary->setHtml(lines.join(QStringLiteral("<br>")));
    m_exportButton->setEnabled(problems.isEmpty() && !m_destination->text().trimmed().isEmpty());
}

QString ExportDialog::destination() const {
    return m_destination->text().trimmed();
}

QString ExportDialog::mode() const {
    return m_mode->currentData().toString();
}

QString ExportDialog::worldName() const {
    const QString name = m_worldName->text().trimmed();
    return name.isEmpty() ? tr("world") : name;
}

QString ExportDialog::numberMode() const {
    return m_numberMode->currentData().toString();
}

bool ExportDialog::arenaPreset() const {
    return m_arenaPreset->isChecked();
}

} // namespace chunkdaddy
