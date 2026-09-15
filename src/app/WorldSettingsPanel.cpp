#include "app/WorldSettingsPanel.h"

#include <QCheckBox>
#include <QComboBox>
#include <QDoubleSpinBox>
#include <QFormLayout>
#include <QHBoxLayout>
#include <QJsonArray>
#include <QLabel>
#include <QLineEdit>
#include <QPushButton>
#include <QScrollArea>
#include <QSignalBlocker>
#include <QSlider>
#include <QSpinBox>
#include <QTabWidget>
#include <QVBoxLayout>
#include <limits>

namespace chunkdaddy {
namespace {

/// A spin box can only span int. The schema speaks in 64-bit ranges because a few level
/// settings really are longs, so a range is narrowed to what the control can represent
/// rather than being allowed to wrap round into a negative bound.
int clampToInt(double value) {
    if (value <= static_cast<double>(std::numeric_limits<int>::min())) {
        return std::numeric_limits<int>::min();
    }
    if (value >= static_cast<double>(std::numeric_limits<int>::max())) {
        return std::numeric_limits<int>::max();
    }
    return static_cast<int>(value);
}

/// Sliders are only worth having where the whole range fits on one, and where dragging
/// through every value in it is a sensible thing to do. A coordinate that spans thirty
/// million is a number to type, not a thing to drag.
bool wantsSlider(int minimum, int maximum) {
    const qint64 span = static_cast<qint64>(maximum) - static_cast<qint64>(minimum);
    return span > 0 && span <= 4096;
}

QString joinNonEmpty(const QStringList& parts, const QString& separator) {
    QStringList kept;
    for (const QString& part : parts) {
        if (!part.isEmpty()) {
            kept.append(part);
        }
    }
    return kept.join(separator);
}

} // namespace

WorldSettingsPanel::WorldSettingsPanel(Workspace* workspace, QWidget* parent)
    : QWidget(parent), m_workspace(workspace) {
    auto* layout = new QVBoxLayout(this);

    m_banner = new QLabel(this);
    m_banner->setWordWrap(true);
    m_banner->setTextFormat(Qt::PlainText);
    layout->addWidget(m_banner);

    m_tabs = new QTabWidget(this);
    layout->addWidget(m_tabs, 1);

    m_status = new QLabel(this);
    m_status->setWordWrap(true);
    m_status->setTextFormat(Qt::PlainText);
    layout->addWidget(m_status);

    auto* adoptRow = new QHBoxLayout();
    adoptRow->addWidget(new QLabel(tr("Copy settings from:"), this));
    m_adoptFrom = new QComboBox(this);
    m_adoptFrom->setSizeAdjustPolicy(QComboBox::AdjustToMinimumContentsLengthWithIcon);
    adoptRow->addWidget(m_adoptFrom, 1);
    m_adoptButton = new QPushButton(tr("Copy"), this);
    adoptRow->addWidget(m_adoptButton);
    layout->addLayout(adoptRow);

    auto* buttonRow = new QHBoxLayout();
    m_revertButton = new QPushButton(tr("Back to source world"), this);
    m_reloadButton = new QPushButton(tr("Reload"), this);
    m_applyButton = new QPushButton(tr("Apply"), this);
    m_applyButton->setDefault(true);
    buttonRow->addWidget(m_revertButton);
    buttonRow->addWidget(m_reloadButton);
    buttonRow->addStretch(1);
    buttonRow->addWidget(m_applyButton);
    layout->addLayout(buttonRow);

    connect(m_applyButton, &QPushButton::clicked, this, &WorldSettingsPanel::applyChanges);
    connect(m_revertButton, &QPushButton::clicked, this, &WorldSettingsPanel::revertToSource);
    connect(m_adoptButton, &QPushButton::clicked, this, &WorldSettingsPanel::adoptFromSelected);
    connect(m_reloadButton, &QPushButton::clicked, this, &WorldSettingsPanel::reload);

    if (m_workspace) {
        connect(m_workspace, &Workspace::documentsChanged, this,
                &WorldSettingsPanel::refreshSourceControls);
    }

    setDocument(nullptr);
}

void WorldSettingsPanel::setDocument(Document* document) {
    m_document = document;
    reload();
}

void WorldSettingsPanel::setStatus(const QString& text, bool problem) {
    m_status->setText(text);
    // Weight rather than colour: this line is read while scanning the panel, and a
    // hard-coded colour would be unreadable against one of the two Qt palettes.
    m_status->setStyleSheet(problem ? QStringLiteral("font-weight: bold;") : QString());
}

void WorldSettingsPanel::setEditingEnabled(bool enabled) {
    m_tabs->setEnabled(enabled);
    m_applyButton->setEnabled(enabled);
    m_reloadButton->setEnabled(enabled);
}

void WorldSettingsPanel::clearFields() {
    m_fields.clear();
    while (m_tabs->count() > 0) {
        QWidget* page = m_tabs->widget(0);
        m_tabs->removeTab(0);
        delete page;
    }
}

void WorldSettingsPanel::reload() {
    if (!m_document || !m_workspace) {
        clearFields();
        m_banner->setText(tr("Open a world to edit its settings."));
        setStatus(QString(), false);
        setEditingEnabled(false);
        m_revertButton->setEnabled(false);
        m_adoptButton->setEnabled(false);
        m_adoptFrom->setEnabled(false);
        return;
    }

    setEditingEnabled(false);
    setStatus(tr("Reading world settings..."), false);
    Document* requested = m_document;
    m_workspace->requestLevelSettings(m_document, [this, requested](const WorkerReply& reply) {
        // The user can switch tabs while this is in flight; a reply for a world that is no
        // longer on screen must not overwrite the one that is.
        if (m_document != requested) {
            return;
        }
        if (!reply.ok) {
            setStatus(tr("The world settings could not be read: %1").arg(reply.describeError()), true);
            setEditingEnabled(false);
            return;
        }
        buildFrom(reply.result);
    });
}

void WorldSettingsPanel::buildFrom(const QJsonObject& payload) {
    m_loading = true;
    clearFields();

    for (const QJsonValue& categoryValue : payload.value(QStringLiteral("categories")).toArray()) {
        const QJsonObject category = categoryValue.toObject();
        const QJsonArray fields = category.value(QStringLiteral("fields")).toArray();
        if (fields.isEmpty()) {
            continue;
        }

        auto* page = new QWidget(m_tabs);
        auto* form = new QFormLayout(page);
        form->setFieldGrowthPolicy(QFormLayout::AllNonFixedFieldsGrow);
        form->setLabelAlignment(Qt::AlignLeft);

        for (const QJsonValue& fieldValue : fields) {
            const QJsonObject field = fieldValue.toObject();
            const QString name = field.value(QStringLiteral("name")).toString();
            const QString kind = field.value(QStringLiteral("kind")).toString();
            const QString label = field.value(QStringLiteral("label")).toString(name);
            const QString hint = field.value(QStringLiteral("hint")).toString();
            const bool locked = field.value(QStringLiteral("locked")).toBool(false);
            const QJsonValue value = field.value(QStringLiteral("value"));

            FieldWidget entry;
            entry.name = name;
            entry.kind = kind;
            entry.original = value;

            QWidget* row = nullptr;

            if (kind == QLatin1String("bool")) {
                auto* box = new QCheckBox(label, page);
                box->setChecked(value.toBool(false));
                entry.editor = box;
                row = box;
            } else if (kind == QLatin1String("choice")) {
                auto* combo = new QComboBox(page);
                entry.optionsAreOrdinals =
                    field.value(QStringLiteral("optionsAreOrdinals")).toBool(false);
                const QJsonArray options = field.value(QStringLiteral("options")).toArray();
                for (int index = 0; index < options.size(); ++index) {
                    combo->addItem(options.at(index).toString());
                }
                if (entry.optionsAreOrdinals) {
                    const int ordinal = value.toInt(0);
                    if (ordinal >= 0 && ordinal < combo->count()) {
                        combo->setCurrentIndex(ordinal);
                    } else {
                        // A world can hold a value outside the named set. Showing it rather
                        // than silently snapping to the nearest name keeps Apply honest:
                        // nothing is sent for a field the user did not touch.
                        combo->addItem(tr("Unknown (%1)").arg(ordinal));
                        combo->setCurrentIndex(combo->count() - 1);
                    }
                } else {
                    const int found = combo->findText(value.toString());
                    combo->setCurrentIndex(found >= 0 ? found : 0);
                }
                entry.editor = combo;
                row = combo;
            } else if (kind == QLatin1String("int")) {
                const int minimum = clampToInt(field.value(QStringLiteral("min")).toDouble(
                    std::numeric_limits<int>::min()));
                const int maximum = clampToInt(field.value(QStringLiteral("max")).toDouble(
                    std::numeric_limits<int>::max()));
                auto* spin = new QSpinBox(page);
                spin->setRange(minimum, maximum);
                spin->setValue(clampToInt(value.toDouble(0)));
                entry.editor = spin;

                if (wantsSlider(minimum, maximum)) {
                    auto* container = new QWidget(page);
                    auto* line = new QHBoxLayout(container);
                    line->setContentsMargins(0, 0, 0, 0);
                    auto* slider = new QSlider(Qt::Horizontal, container);
                    slider->setRange(minimum, maximum);
                    slider->setValue(spin->value());
                    line->addWidget(slider, 1);
                    line->addWidget(spin);
                    // Two views of one value; each blocks the other while it writes so a
                    // rounding step cannot bounce between them.
                    connect(slider, &QSlider::valueChanged, spin, [spin](int next) {
                        const QSignalBlocker blocker(spin);
                        spin->setValue(next);
                    });
                    connect(spin, QOverload<int>::of(&QSpinBox::valueChanged), slider,
                            [slider](int next) {
                                const QSignalBlocker blocker(slider);
                                slider->setValue(next);
                            });
                    row = container;
                } else {
                    row = spin;
                }
            } else if (kind == QLatin1String("real")) {
                auto* spin = new QDoubleSpinBox(page);
                spin->setDecimals(3);
                spin->setRange(field.value(QStringLiteral("min")).toDouble(-1e9),
                               field.value(QStringLiteral("max")).toDouble(1e9));
                spin->setValue(value.toDouble(0));
                entry.editor = spin;
                row = spin;
            } else if (kind == QLatin1String("text")) {
                auto* edit = new QLineEdit(value.toString(), page);
                entry.editor = edit;
                row = edit;
            } else {
                continue;
            }

            if (locked) {
                entry.editor->setEnabled(false);
                if (row != entry.editor) {
                    row->setEnabled(false);
                }
            }
            if (!hint.isEmpty()) {
                entry.editor->setToolTip(hint);
                row->setToolTip(hint);
            }

            if (kind == QLatin1String("bool")) {
                // A checkbox carries its own label; a second one beside it reads as a
                // repeated word rather than a heading.
                form->addRow(row);
            } else {
                form->addRow(label, row);
            }
            m_fields.append(entry);
        }

        auto* scroll = new QScrollArea(m_tabs);
        scroll->setWidgetResizable(true);
        scroll->setWidget(page);
        m_tabs->addTab(scroll, category.value(QStringLiteral("name")).toString());
    }

    QStringList bannerParts;
    const QJsonArray experiments = payload.value(QStringLiteral("sourceExperiments")).toArray();
    if (!experiments.isEmpty()) {
        QStringList names;
        for (const QJsonValue& value : experiments) {
            names.append(value.toString());
        }
        bannerParts.append(
            tr("Experiments carried over from the source world: %1. These are written into the "
               "export exactly as they are. gametest is the Beta APIs switch; a behaviour pack "
               "whose manifest asks for a beta script module loads with no scripts and no error "
               "message when it is missing.")
                .arg(names.join(QStringLiteral(", "))));
    } else if (payload.value(QStringLiteral("preservesSourceLevelData")).toBool(false)) {
        bannerParts.append(
            tr("The source world's level.dat is carried into the export, but it had no "
               "experiments switched on."));
    } else if (payload.value(QStringLiteral("hasSource")).toBool(false)) {
        bannerParts.append(
            tr("This world came from a Java source, so only the settings below are carried "
               "across; Bedrock-only level.dat tags do not transfer between editions."));
    } else {
        bannerParts.append(tr("This world was created here, so it starts from default settings."));
    }
    m_banner->setText(joinNonEmpty(bannerParts, QStringLiteral(" ")));

    m_loading = false;
    setEditingEnabled(true);
    setStatus(QString(), false);
    refreshSourceControls();
}

QJsonValue WorldSettingsPanel::currentValue(const FieldWidget& field) const {
    if (auto* box = qobject_cast<QCheckBox*>(field.editor)) {
        return QJsonValue(box->isChecked());
    }
    if (auto* combo = qobject_cast<QComboBox*>(field.editor)) {
        if (field.optionsAreOrdinals) {
            return QJsonValue(combo->currentIndex());
        }
        return QJsonValue(combo->currentText());
    }
    if (auto* spin = qobject_cast<QSpinBox*>(field.editor)) {
        return QJsonValue(spin->value());
    }
    if (auto* spin = qobject_cast<QDoubleSpinBox*>(field.editor)) {
        return QJsonValue(spin->value());
    }
    if (auto* edit = qobject_cast<QLineEdit*>(field.editor)) {
        return QJsonValue(edit->text());
    }
    return QJsonValue();
}

QJsonObject WorldSettingsPanel::collectChanges() const {
    QJsonObject changes;
    for (const FieldWidget& field : m_fields) {
        if (!field.editor || !field.editor->isEnabled()) {
            continue; // Locked fields are shown, never sent.
        }
        const QJsonValue value = currentValue(field);
        if (value.isNull() || value.isUndefined()) {
            continue;
        }
        // Compare against what the worker last reported rather than against a default, so
        // an unchanged field is never sent and a setting cannot be "reset" by opening the
        // panel and pressing Apply.
        const bool same = field.kind == QLatin1String("real")
                              ? qFuzzyCompare(value.toDouble() + 1.0, field.original.toDouble() + 1.0)
                              : value == field.original;
        if (!same) {
            changes.insert(field.name, value);
        }
    }
    return changes;
}

void WorldSettingsPanel::applyChanges() {
    if (!m_document || !m_workspace || m_loading) {
        return;
    }
    const QJsonObject changes = collectChanges();
    if (changes.isEmpty()) {
        setStatus(tr("Nothing has been changed."), false);
        return;
    }

    setEditingEnabled(false);
    setStatus(tr("Applying %n change(s)...", nullptr, changes.size()), false);
    Document* requested = m_document;
    const int requestedCount = changes.size();
    m_workspace->applyLevelSettings(
        m_document, changes, [this, requested, requestedCount](const WorkerReply& reply) {
            if (m_document != requested) {
                return;
            }
            if (!reply.ok) {
                setStatus(tr("Nothing was changed: %1").arg(reply.describeError()), true);
                setEditingEnabled(true);
                return;
            }
            QStringList rejected;
            for (const QJsonValue& value : reply.result.value(QStringLiteral("rejected")).toArray()) {
                rejected.append(value.toString());
            }
            const int applied =
                reply.result.value(QStringLiteral("applied")).toArray().size();

            // Rebuild from the reply, so every control shows the value the world actually
            // holds now rather than the one that was typed into it.
            buildFrom(reply.result);
            if (rejected.isEmpty()) {
                setStatus(tr("Applied %n setting(s).", nullptr, applied), false);
            } else {
                setStatus(tr("Applied %1 of %2. Not applied: %3")
                              .arg(applied)
                              .arg(requestedCount)
                              .arg(rejected.join(QStringLiteral("; "))),
                          true);
            }
            emit settingsChanged();
        });
}

void WorldSettingsPanel::revertToSource() {
    if (!m_document || !m_workspace) {
        return;
    }
    setEditingEnabled(false);
    Document* requested = m_document;
    m_workspace->revertLevelSettingsToSource(m_document, [this, requested](const WorkerReply& reply) {
        if (m_document != requested) {
            return;
        }
        if (!reply.ok) {
            setStatus(tr("The settings were not changed: %1").arg(reply.describeError()), true);
            setEditingEnabled(true);
            return;
        }
        buildFrom(reply.result);
        if (reply.result.value(QStringLiteral("reverted")).toBool(false)) {
            setStatus(tr("Back to the settings the source world had."), false);
            emit settingsChanged();
        } else {
            setStatus(tr("This world was not opened from an existing one, so there are no "
                         "source settings to go back to."),
                      true);
        }
    });
}

void WorldSettingsPanel::adoptFromSelected() {
    if (!m_document || !m_workspace || !m_adoptFrom->isEnabled()) {
        return;
    }
    const QString sourceId = m_adoptFrom->currentData().toString();
    Document* source = m_workspace->documentById(sourceId);
    if (!source || source == m_document) {
        setStatus(tr("Choose a different open world to copy settings from."), true);
        return;
    }
    setEditingEnabled(false);
    Document* requested = m_document;
    const QString sourceName = source->name();
    m_workspace->adoptLevelSettings(
        m_document, source, [this, requested, sourceName](const WorkerReply& reply) {
            if (m_document != requested) {
                return;
            }
            if (!reply.ok) {
                setStatus(tr("The settings were not copied: %1").arg(reply.describeError()), true);
                setEditingEnabled(true);
                return;
            }
            buildFrom(reply.result);
            // Worth saying out loud: the copy covers the settings, not the source world's
            // own raw level.dat tags, which stay with the world they were read from.
            setStatus(tr("Copied the settings from \"%1\". Experiments and other raw level.dat "
                         "tags are not copied; each world keeps its own.")
                          .arg(sourceName),
                      false);
            emit settingsChanged();
        });
}

void WorldSettingsPanel::refreshSourceControls() {
    m_revertButton->setEnabled(m_document != nullptr && m_document->hasSourceLevelSettings());
    if (!m_document) {
        m_revertButton->setToolTip(QString());
    } else if (!m_document->hasSourceLevelSettings()) {
        m_revertButton->setToolTip(
            tr("Only available for a world that was opened from an existing one."));
    } else {
        m_revertButton->setToolTip(tr("Discard every change made here and use the settings the "
                                      "source world was saved with."));
    }

    if (!m_workspace) {
        return;
    }
    const QString previous = m_adoptFrom->currentData().toString();
    const QSignalBlocker blocker(m_adoptFrom);
    m_adoptFrom->clear();
    for (Document* document : m_workspace->documents()) {
        if (document == m_document) {
            continue;
        }
        m_adoptFrom->addItem(document->name(), document->documentId());
    }
    const int restored = m_adoptFrom->findData(previous);
    if (restored >= 0) {
        m_adoptFrom->setCurrentIndex(restored);
    }
    const bool available = m_document != nullptr && m_adoptFrom->count() > 0;
    m_adoptFrom->setEnabled(available);
    m_adoptButton->setEnabled(available);
}

} // namespace chunkdaddy
