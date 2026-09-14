#include "view/ChunkView.h"

#include <QKeyEvent>
#include <QMouseEvent>
#include <QPainter>
#include <QPainterPath>
#include <QResizeEvent>
#include <QWheelEvent>
#include <algorithm>
#include <cmath>

namespace chunkdaddy {
namespace {

constexpr double kMinZoom = 0.01;   // one pixel per 100 blocks
constexpr double kMaxZoom = 8.0;    // eight pixels per block
constexpr double kBlockCellZoom = 2.0;  // above this, draw individual block cells

const QColor kBackground(18, 18, 20);
const QColor kAbsent(24, 24, 28);
const QColor kGeneratedVoid(38, 40, 52);
const QColor kGridLine(60, 62, 70);
const QColor kSelection(88, 166, 255);
const QColor kExportRect(255, 196, 64);
const QColor kArenaOutline(120, 220, 150);
const QColor kArenaInvalid(240, 110, 110);
const QColor kPaste(180, 140, 255);

} // namespace

ChunkView::ChunkView(QWidget* parent) : QWidget(parent) {
    setFocusPolicy(Qt::StrongFocus);
    setMouseTracking(true);
    setAutoFillBackground(false);
    setAttribute(Qt::WA_OpaquePaintEvent, true);
    setMinimumSize(320, 240);

    connect(&m_tiles, &TileCache::tilesChanged, this, [this](const ChunkRect&) { update(); });
}

void ChunkView::setDocument(Document* document) {
    if (m_document == document) {
        return;
    }
    if (m_document) {
        disconnect(m_document, nullptr, this, nullptr);
    }
    m_document = document;
    m_tiles.clear();
    cancelPastePreview();
    if (m_document) {
        connect(m_document, &Document::stateChanged, this, [this] {
            // A new revision invalidates everything the preview showed.
            m_tiles.clear();
            requestMissingTiles();
            update();
        });
        if (const auto bounds = m_document->contentBounds()) {
            zoomToFit(*bounds);
        }
    }
    update();
}

void ChunkView::setTool(Tool tool) {
    m_tool = tool;
    setCursor(tool == Tool::Move ? Qt::SizeAllCursor
                                 : (tool == Tool::PlaceWorldSpawn ? Qt::CrossCursor : Qt::ArrowCursor));
    update();
}

void ChunkView::beginPastePreview(const ChunkRect& relativeBounds) {
    m_pastePreview = relativeBounds;
    m_pasteOrigin = m_hasHover ? m_hoverChunk : QPoint(0, 0);
    setFocus();
    update();
}

void ChunkView::cancelPastePreview() {
    if (m_pastePreview.has_value()) {
        m_pastePreview.reset();
        update();
    }
}

std::optional<ChunkRect> ChunkView::pastePreviewRect() const {
    if (!m_pastePreview) {
        return std::nullopt;
    }
    return ChunkRect::ofSize(m_pasteOrigin.x(), m_pasteOrigin.y(),
                             m_pastePreview->widthChunks(), m_pastePreview->lengthChunks());
}

void ChunkView::setPastePreviewOrigin(int chunkX, int chunkZ) {
    m_pasteOrigin = QPoint(chunkX, chunkZ);
    update();
}

void ChunkView::setHeightSlice(int sliceY) {
    if (m_sliceY == sliceY) {
        return;
    }
    m_sliceY = sliceY;
    // The slice changes what every column samples, so cached tiles are stale.
    m_tiles.clear();
    requestMissingTiles();
    update();
}

void ChunkView::setShowChunkGrid(bool show) {
    m_showChunkGrid = show;
    update();
}

void ChunkView::setShowArenaLabels(bool show) {
    m_showArenaLabels = show;
    update();
}

void ChunkView::centreOn(int chunkX, int chunkZ) {
    const double centreBlockX = chunkToBlock(chunkX) + 8.0;
    const double centreBlockZ = chunkToBlock(chunkZ) + 8.0;
    m_origin = QPointF(centreBlockX - width() / (2.0 * m_zoom),
                       centreBlockZ - height() / (2.0 * m_zoom));
    requestMissingTiles();
    update();
}

void ChunkView::zoomToFit(const ChunkRect& area) {
    const double blocksWide = area.widthChunks() * 16.0;
    const double blocksLong = area.lengthChunks() * 16.0;
    if (blocksWide <= 0 || blocksLong <= 0 || width() <= 0 || height() <= 0) {
        return;
    }
    const double fit = std::min(width() / blocksWide, height() / blocksLong) * 0.92;
    m_zoom = std::clamp(fit, kMinZoom, kMaxZoom);
    centreOn((area.minX() + area.maxX()) / 2, (area.minZ() + area.maxZ()) / 2);
}

QPointF ChunkView::worldToWidget(double blockX, double blockZ) const {
    return QPointF((blockX - m_origin.x()) * m_zoom, (blockZ - m_origin.y()) * m_zoom);
}

QPointF ChunkView::widgetToWorld(const QPointF& widgetPos) const {
    return QPointF(widgetPos.x() / m_zoom + m_origin.x(), widgetPos.y() / m_zoom + m_origin.y());
}

QPoint ChunkView::chunkAt(const QPointF& widgetPos) const {
    const QPointF world = widgetToWorld(widgetPos);
    // Floor, not truncate: a world position of -0.5 belongs to chunk -1.
    return QPoint(blockToChunk(static_cast<int>(std::floor(world.x()))),
                  blockToChunk(static_cast<int>(std::floor(world.y()))));
}

BlockPos ChunkView::blockAt(const QPointF& widgetPos) const {
    const QPointF world = widgetToWorld(widgetPos);
    return BlockPos{static_cast<int>(std::floor(world.x())), m_sliceY,
                    static_cast<int>(std::floor(world.y()))};
}

ChunkRect ChunkView::visibleChunks() const {
    const QPointF topLeft = widgetToWorld(QPointF(0, 0));
    const QPointF bottomRight = widgetToWorld(QPointF(width(), height()));
    const int minChunkX = blockToChunk(static_cast<int>(std::floor(topLeft.x())));
    const int minChunkZ = blockToChunk(static_cast<int>(std::floor(topLeft.y())));
    const int maxChunkX = blockToChunk(static_cast<int>(std::floor(bottomRight.x())));
    const int maxChunkZ = blockToChunk(static_cast<int>(std::floor(bottomRight.y())));
    return ChunkRect(minChunkX, minChunkZ, maxChunkX, maxChunkZ);
}

void ChunkView::requestMissingTiles() {
    if (!m_document) {
        return;
    }
    const ChunkRect visible = visibleChunks();
    // Never ask for more than the worker will render in one call.
    if (visible.columnCount() > 4096) {
        return;
    }
    const QVector<ChunkRect> missing = m_tiles.missingRegions(visible);
    if (!missing.isEmpty()) {
        emit tilesNeeded(missing);
    }
}

void ChunkView::setZoom(double zoom, const QPointF& anchorWidgetPos) {
    const double clamped = std::clamp(zoom, kMinZoom, kMaxZoom);
    if (qFuzzyCompare(clamped, m_zoom)) {
        return;
    }
    // Keep the world position under the cursor fixed while zooming.
    const QPointF anchorWorld = widgetToWorld(anchorWidgetPos);
    m_zoom = clamped;
    m_origin = QPointF(anchorWorld.x() - anchorWidgetPos.x() / m_zoom,
                       anchorWorld.y() - anchorWidgetPos.y() / m_zoom);
    requestMissingTiles();
    update();
}

// ---------------------------------------------------------------------------
// Painting
// ---------------------------------------------------------------------------

void ChunkView::paintEvent(QPaintEvent*) {
    QPainter painter(this);
    painter.fillRect(rect(), kBackground);
    if (!m_document) {
        painter.setPen(QColor(150, 150, 160));
        painter.drawText(rect(), Qt::AlignCenter,
                         tr("No world open.\nFile > New Void Bedrock World, or drop a world here."));
        return;
    }

    const ChunkRect visible = visibleChunks();
    painter.setRenderHint(QPainter::SmoothPixmapTransform, m_zoom < 1.0);

    drawTiles(painter, visible);
    drawExportRectangle(painter);
    if (m_showChunkGrid) {
        drawChunkGrid(painter, visible);
    }
    drawArenas(painter, visible);
    drawSelection(painter);
    drawPastePreview(painter);
    drawWorldSpawn(painter);
    drawLegend(painter);
}

void ChunkView::drawTiles(QPainter& painter, const ChunkRect& visible) {
    const double chunkPixels = 16.0 * m_zoom;

    if (chunkPixels < 3.0) {
        // Too small for per-chunk work: paint whole pages scaled down.
        const int minPageX = static_cast<int>(std::floor(visible.minX() / double(TileCache::kPageChunks)));
        const int maxPageX = static_cast<int>(std::floor(visible.maxX() / double(TileCache::kPageChunks)));
        const int minPageZ = static_cast<int>(std::floor(visible.minZ() / double(TileCache::kPageChunks)));
        const int maxPageZ = static_cast<int>(std::floor(visible.maxZ() / double(TileCache::kPageChunks)));
        for (int pageX = minPageX; pageX <= maxPageX; ++pageX) {
            for (int pageZ = minPageZ; pageZ <= maxPageZ; ++pageZ) {
                const QImage page = m_tiles.pageImage(pageX, pageZ);
                const QPointF topLeft = worldToWidget(chunkToBlock(pageX * TileCache::kPageChunks),
                                                      chunkToBlock(pageZ * TileCache::kPageChunks));
                const double side = TileCache::kPageChunks * chunkPixels;
                painter.drawImage(QRectF(topLeft, QSizeF(side, side)), page);
            }
        }
        return;
    }

    for (int cx = visible.minX(); cx <= visible.maxX(); ++cx) {
        for (int cz = visible.minZ(); cz <= visible.maxZ(); ++cz) {
            const QPointF topLeft = worldToWidget(chunkToBlock(cx), chunkToBlock(cz));
            const QRectF cell(topLeft, QSizeF(chunkPixels, chunkPixels));
            const ColumnState state = m_tiles.state(cx, cz);

            switch (state) {
            case ColumnState::Content: {
                const QImage image = m_tiles.chunkImage(cx, cz);
                if (image.isNull()) {
                    painter.fillRect(cell, kAbsent);
                } else {
                    painter.drawImage(cell, image);
                }
                break;
            }
            case ColumnState::GeneratedVoid:
                // Distinct from absent: this column will be written as an empty column.
                painter.fillRect(cell, kGeneratedVoid);
                break;
            case ColumnState::Absent:
                painter.fillRect(cell, kAbsent);
                break;
            }
        }
    }
}

void ChunkView::drawChunkGrid(QPainter& painter, const ChunkRect& visible) {
    const double chunkPixels = 16.0 * m_zoom;
    if (chunkPixels < 6.0) {
        return;
    }
    painter.setPen(QPen(kGridLine, 1.0));
    for (int cx = visible.minX(); cx <= visible.maxX() + 1; ++cx) {
        const double x = worldToWidget(chunkToBlock(cx), 0).x();
        painter.drawLine(QPointF(x, 0), QPointF(x, height()));
    }
    for (int cz = visible.minZ(); cz <= visible.maxZ() + 1; ++cz) {
        const double y = worldToWidget(0, chunkToBlock(cz)).y();
        painter.drawLine(QPointF(0, y), QPointF(width(), y));
    }

    if (m_zoom >= kBlockCellZoom) {
        // Close in, show individual block cells so marker placement is unambiguous.
        painter.setPen(QPen(QColor(kGridLine.red(), kGridLine.green(), kGridLine.blue(), 70), 1.0));
        for (int cx = visible.minX(); cx <= visible.maxX() + 1; ++cx) {
            for (int block = 1; block < 16; ++block) {
                const double x = worldToWidget(chunkToBlock(cx) + block, 0).x();
                painter.drawLine(QPointF(x, 0), QPointF(x, height()));
            }
        }
        for (int cz = visible.minZ(); cz <= visible.maxZ() + 1; ++cz) {
            for (int block = 1; block < 16; ++block) {
                const double y = worldToWidget(0, chunkToBlock(cz) + block).y();
                painter.drawLine(QPointF(0, y), QPointF(width(), y));
            }
        }
    }
}

void ChunkView::drawExportRectangle(QPainter& painter) {
    const auto rectangle = m_document->exportRectangle();
    if (!rectangle) {
        return;
    }
    const QPointF topLeft = worldToWidget(chunkToBlock(rectangle->minX()), chunkToBlock(rectangle->minZ()));
    const QPointF bottomRight =
        worldToWidget(chunkToBlock(rectangle->maxX()) + 16, chunkToBlock(rectangle->maxZ()) + 16);
    painter.setPen(QPen(kExportRect, 2.0, Qt::DashLine));
    painter.setBrush(Qt::NoBrush);
    painter.drawRect(QRectF(topLeft, bottomRight));
}

void ChunkView::drawArenas(QPainter& painter, const ChunkRect& visible) {
    const double chunkPixels = 16.0 * m_zoom;
    const bool labels = m_showArenaLabels && chunkPixels >= 10.0;

    for (const ArenaInfo& arena : m_document->arenas()) {
        if (!arena.chunkBounds.intersects(visible)) {
            continue;
        }
        const QPointF topLeft =
            worldToWidget(chunkToBlock(arena.chunkBounds.minX()), chunkToBlock(arena.chunkBounds.minZ()));
        const QPointF bottomRight = worldToWidget(chunkToBlock(arena.chunkBounds.maxX()) + 16,
                                                  chunkToBlock(arena.chunkBounds.maxZ()) + 16);
        const QRectF box(topLeft, bottomRight);

        painter.setBrush(Qt::NoBrush);
        painter.setPen(QPen(arena.needsRevalidation ? kArenaInvalid : kArenaOutline,
                            arena.needsRevalidation ? 2.0 : 1.0));
        painter.drawRect(box);

        if (labels && box.width() > 40) {
            painter.setPen(arena.needsRevalidation ? kArenaInvalid : kArenaOutline);
            QString label = arena.exportId;
            if (arena.needsRevalidation) {
                label += tr(" (needs revalidation)");
            }
            painter.drawText(box.adjusted(3, 2, -3, -3), Qt::AlignTop | Qt::AlignLeft, label);
        }
    }
}

void ChunkView::drawSelection(QPainter& painter) {
    const Selection& selection = m_document->selection();

    QPainterPath path;
    if (!selection.isEmpty()) {
        const ChunkRect box = selection.bounds();
        for (int cx = box.minX(); cx <= box.maxX(); ++cx) {
            for (int cz = box.minZ(); cz <= box.maxZ(); ++cz) {
                if (!selection.contains(cx, cz)) {
                    continue;
                }
                const QPointF topLeft = worldToWidget(chunkToBlock(cx), chunkToBlock(cz));
                path.addRect(QRectF(topLeft, QSizeF(16.0 * m_zoom, 16.0 * m_zoom)));
            }
        }
    }

    // The rectangle currently being dragged out, before it is committed.
    if (m_drag == DragMode::SelectRect || m_drag == DragMode::SubtractRect) {
        const ChunkRect live = ChunkRect::fromCorners(m_dragStartChunk.x(), m_dragStartChunk.y(),
                                                      m_dragCurrentChunk.x(), m_dragCurrentChunk.y());
        const QPointF topLeft = worldToWidget(chunkToBlock(live.minX()), chunkToBlock(live.minZ()));
        const QPointF bottomRight =
            worldToWidget(chunkToBlock(live.maxX()) + 16, chunkToBlock(live.maxZ()) + 16);
        painter.setPen(QPen(m_drag == DragMode::SubtractRect ? QColor(255, 140, 140) : kSelection,
                            1.5, Qt::DashLine));
        painter.setBrush(Qt::NoBrush);
        painter.drawRect(QRectF(topLeft, bottomRight));
    }

    if (path.isEmpty()) {
        return;
    }
    QColor fill = kSelection;
    fill.setAlpha(60);
    painter.setBrush(fill);
    painter.setPen(QPen(kSelection, 1.5));

    if (m_drag == DragMode::MoveContent) {
        // Show where the move would land, keeping the source visible underneath.
        const int dChunkX = m_dragCurrentChunk.x() - m_dragStartChunk.x();
        const int dChunkZ = m_dragCurrentChunk.y() - m_dragStartChunk.y();
        painter.save();
        painter.translate(dChunkX * 16.0 * m_zoom, dChunkZ * 16.0 * m_zoom);
        painter.drawPath(path);
        painter.restore();
        QColor ghost = kSelection;
        ghost.setAlpha(25);
        painter.setBrush(ghost);
        painter.setPen(QPen(kSelection, 1.0, Qt::DotLine));
    }
    painter.drawPath(path);
}

void ChunkView::drawPastePreview(QPainter& painter) {
    const auto preview = pastePreviewRect();
    if (!preview) {
        return;
    }
    const QPointF topLeft = worldToWidget(chunkToBlock(preview->minX()), chunkToBlock(preview->minZ()));
    const QPointF bottomRight =
        worldToWidget(chunkToBlock(preview->maxX()) + 16, chunkToBlock(preview->maxZ()) + 16);
    QColor fill = kPaste;
    fill.setAlpha(50);
    painter.setBrush(fill);
    painter.setPen(QPen(kPaste, 2.0));
    painter.drawRect(QRectF(topLeft, bottomRight));

    painter.setPen(kPaste);
    painter.drawText(QRectF(topLeft, bottomRight).adjusted(4, 4, -4, -4), Qt::AlignTop | Qt::AlignLeft,
                     tr("Paste at chunk %1, %2\nClick to commit, Esc to cancel")
                         .arg(preview->minX())
                         .arg(preview->minZ()));
}

void ChunkView::drawWorldSpawn(QPainter& painter) {
    const BlockPos spawn = m_document->worldSpawn();
    const QPointF centre = worldToWidget(spawn.x + 0.5, spawn.z + 0.5);
    if (!rect().contains(centre.toPoint())) {
        return;
    }
    painter.setPen(QPen(QColor(255, 255, 255), 1.5));
    painter.setBrush(QColor(255, 255, 255, 40));
    painter.drawEllipse(centre, 6.0, 6.0);
    painter.drawLine(centre + QPointF(-9, 0), centre + QPointF(9, 0));
    painter.drawLine(centre + QPointF(0, -9), centre + QPointF(0, 9));
}

void ChunkView::drawLegend(QPainter& painter) {
    // A black square alone cannot say whether a gap will be serialized, so the legend is
    // always on screen rather than hidden in a menu.
    const int boxSize = 10;
    const int padding = 8;
    const QStringList labels{tr("content"), tr("generated void"), tr("absent")};
    const QList<QColor> colours{QColor(120, 170, 120), kGeneratedVoid, kAbsent};

    QFontMetrics metrics(painter.font());
    int widest = 0;
    for (const QString& label : labels) {
        widest = std::max(widest, metrics.horizontalAdvance(label));
    }
    const int boxWidth = padding * 3 + boxSize + widest;
    const int boxHeight = padding * 2 + labels.size() * (boxSize + 6) - 6;
    const QRect legend(padding, height() - boxHeight - padding, boxWidth, boxHeight);

    painter.setPen(Qt::NoPen);
    painter.setBrush(QColor(0, 0, 0, 160));
    painter.drawRoundedRect(legend, 4, 4);

    for (int i = 0; i < labels.size(); ++i) {
        const int y = legend.top() + padding + i * (boxSize + 6);
        painter.setPen(QPen(kGridLine, 1));
        painter.setBrush(colours.at(i));
        painter.drawRect(QRect(legend.left() + padding, y, boxSize, boxSize));
        painter.setPen(QColor(220, 220, 225));
        painter.drawText(QPoint(legend.left() + padding * 2 + boxSize, y + boxSize), labels.at(i));
    }
}

// ---------------------------------------------------------------------------
// Input
// ---------------------------------------------------------------------------

void ChunkView::mousePressEvent(QMouseEvent* event) {
    if (!m_document) {
        return;
    }
    const QPoint chunk = chunkAt(event->position());
    m_dragStartWidget = event->position();
    m_dragStartOrigin = m_origin;
    m_dragStartChunk = chunk;
    m_dragCurrentChunk = chunk;

    if (m_pastePreview && event->button() == Qt::LeftButton) {
        emit pasteCommitted(m_pasteOrigin.x(), m_pasteOrigin.y());
        return;
    }

    if (event->button() == Qt::MiddleButton || (event->button() == Qt::LeftButton && m_spaceHeld)) {
        m_drag = DragMode::Pan;
        setCursor(Qt::ClosedHandCursor);
        return;
    }
    if (event->button() != Qt::LeftButton) {
        return;
    }

    if (m_tool == Tool::PlaceWorldSpawn) {
        emit worldSpawnPicked(blockAt(event->position()));
        return;
    }
    if (m_tool == Tool::Move) {
        if (m_document->selection().contains(chunk.x(), chunk.y())) {
            m_drag = DragMode::MoveContent;
        }
        return;
    }

    if (event->modifiers().testFlag(Qt::AltModifier)) {
        m_drag = DragMode::SubtractRect;
    } else {
        m_drag = DragMode::SelectRect;
        if (!event->modifiers().testFlag(Qt::ShiftModifier)) {
            m_document->selection().clear();
        }
    }
    update();
}

void ChunkView::mouseMoveEvent(QMouseEvent* event) {
    if (!m_document) {
        return;
    }
    const QPoint chunk = chunkAt(event->position());
    m_hoverChunk = chunk;
    m_hasHover = true;

    if (m_pastePreview) {
        m_pasteOrigin = chunk;
        update();
    }

    switch (m_drag) {
    case DragMode::Pan: {
        const QPointF delta = event->position() - m_dragStartWidget;
        m_origin = m_dragStartOrigin - QPointF(delta.x() / m_zoom, delta.y() / m_zoom);
        requestMissingTiles();
        update();
        break;
    }
    case DragMode::SelectRect:
    case DragMode::SubtractRect:
    case DragMode::MoveContent:
        m_dragCurrentChunk = chunk;
        update();
        break;
    case DragMode::None:
        break;
    }

    emit cursorMoved(blockAt(event->position()), chunk, m_tiles.state(chunk.x(), chunk.y()));
}

void ChunkView::mouseReleaseEvent(QMouseEvent* event) {
    if (!m_document) {
        return;
    }
    const DragMode finished = m_drag;
    m_drag = DragMode::None;
    setCursor(m_tool == Tool::Move ? Qt::SizeAllCursor : Qt::ArrowCursor);

    switch (finished) {
    case DragMode::SelectRect: {
        const ChunkRect rect = ChunkRect::fromCorners(m_dragStartChunk.x(), m_dragStartChunk.y(),
                                                      m_dragCurrentChunk.x(), m_dragCurrentChunk.y());
        m_document->selection().add(rect);
        m_document->notifySelectionChanged();
        emit selectionChanged();
        break;
    }
    case DragMode::SubtractRect: {
        const ChunkRect rect = ChunkRect::fromCorners(m_dragStartChunk.x(), m_dragStartChunk.y(),
                                                      m_dragCurrentChunk.x(), m_dragCurrentChunk.y());
        m_document->selection().subtract(rect);
        m_document->notifySelectionChanged();
        emit selectionChanged();
        break;
    }
    case DragMode::MoveContent: {
        const int dChunkX = m_dragCurrentChunk.x() - m_dragStartChunk.x();
        const int dChunkZ = m_dragCurrentChunk.y() - m_dragStartChunk.y();
        if (dChunkX != 0 || dChunkZ != 0) {
            // Movements are on the chunk lattice, so a horizontal move is always a
            // multiple of sixteen blocks.
            emit moveRequested(dChunkX, dChunkZ);
        }
        break;
    }
    case DragMode::Pan:
    case DragMode::None:
        break;
    }
    Q_UNUSED(event);
    update();
}

void ChunkView::mouseDoubleClickEvent(QMouseEvent* event) {
    if (!m_document || event->button() != Qt::LeftButton) {
        return;
    }
    const QPoint chunk = chunkAt(event->position());
    if (const ArenaInfo* arena = m_document->arenaAt(chunk.x(), chunk.y())) {
        // Double clicking an arena selects the whole arena, which is what a user almost
        // always means; a partial arena selection has to be deliberate.
        m_document->selection().set(arena->chunkBounds);
        m_document->notifySelectionChanged();
        emit selectionChanged();
        emit arenaActivated(arena->id);
        update();
    }
}

void ChunkView::wheelEvent(QWheelEvent* event) {
    const double steps = event->angleDelta().y() / 120.0;
    if (qFuzzyIsNull(steps)) {
        return;
    }
    setZoom(m_zoom * std::pow(1.2, steps), event->position());
    event->accept();
}

void ChunkView::keyPressEvent(QKeyEvent* event) {
    if (event->key() == Qt::Key_Space) {
        m_spaceHeld = true;
        setCursor(Qt::OpenHandCursor);
        event->accept();
        return;
    }
    if (event->key() == Qt::Key_Escape) {
        if (m_pastePreview) {
            // Escape cancels the preview without changing the document.
            cancelPastePreview();
        } else if (m_document && !m_document->selection().isEmpty()) {
            m_document->selection().clear();
            m_document->notifySelectionChanged();
            emit selectionChanged();
            update();
        }
        event->accept();
        return;
    }
    QWidget::keyPressEvent(event);
}

void ChunkView::keyReleaseEvent(QKeyEvent* event) {
    if (event->key() == Qt::Key_Space) {
        m_spaceHeld = false;
        setCursor(m_tool == Tool::Move ? Qt::SizeAllCursor : Qt::ArrowCursor);
        event->accept();
        return;
    }
    QWidget::keyReleaseEvent(event);
}

void ChunkView::resizeEvent(QResizeEvent* event) {
    QWidget::resizeEvent(event);
    requestMissingTiles();
}

void ChunkView::leaveEvent(QEvent* event) {
    m_hasHover = false;
    QWidget::leaveEvent(event);
}

} // namespace chunkdaddy
