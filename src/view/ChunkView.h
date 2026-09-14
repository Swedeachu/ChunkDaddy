#pragma once

#include "document/Document.h"
#include "layout/Geometry.h"
#include "view/TileCache.h"

#include <QPointF>
#include <QWidget>
#include <optional>

namespace chunkdaddy {

/// The top-down chunk editor viewport.
///
/// Rendering is tile based: prepared 16x16 chunk images are grouped into pages, and at
/// distant zoom the pages are drawn scaled rather than the individual chunks. Nothing
/// here allocates an image covering the whole composition.
class ChunkView : public QWidget {
    Q_OBJECT

public:
    /// What a left drag does.
    enum class Tool {
        Select,
        /// Drag the selected content across the chunk lattice.
        Move,
        /// Set the world spawn by clicking.
        PlaceWorldSpawn
    };

    explicit ChunkView(QWidget* parent = nullptr);

    void setDocument(Document* document);
    Document* document() const noexcept { return m_document; }
    TileCache& tiles() noexcept { return m_tiles; }

    void setTool(Tool tool);
    Tool tool() const noexcept { return m_tool; }

    /// Show a paste preview that follows the cursor until it is committed or cancelled.
    void beginPastePreview(const ChunkRect& relativeBounds);
    void cancelPastePreview();
    bool hasPastePreview() const noexcept { return m_pastePreview.has_value(); }
    /// Where the preview's minimum corner currently sits.
    std::optional<ChunkRect> pastePreviewRect() const;
    /// Move the preview to an exact position, for numeric entry.
    void setPastePreviewOrigin(int chunkX, int chunkZ);

    void setHeightSlice(int sliceY);
    int heightSlice() const noexcept { return m_sliceY; }

    void setShowChunkGrid(bool show);
    void setShowArenaLabels(bool show);

    /// Centre the view on a chunk without changing zoom.
    void centreOn(int chunkX, int chunkZ);
    void zoomToFit(const ChunkRect& area);

    /// Chunk under a widget position.
    QPoint chunkAt(const QPointF& widgetPos) const;
    /// Block under a widget position, at the current height slice.
    BlockPos blockAt(const QPointF& widgetPos) const;

signals:
    /// Cursor moved; the status bar shows block and chunk coordinates.
    void cursorMoved(const BlockPos& block, const QPoint& chunk, ColumnState state);
    void selectionChanged();
    /// The user finished a move drag: the delta in chunks.
    void moveRequested(int deltaChunkX, int deltaChunkZ);
    /// The user committed a paste at this chunk position.
    void pasteCommitted(int chunkX, int chunkZ);
    void worldSpawnPicked(const BlockPos& block);
    /// Tiles for these regions are not cached and should be fetched.
    void tilesNeeded(const QVector<ChunkRect>& regions);
    void arenaActivated(const QString& arenaId);

protected:
    void paintEvent(QPaintEvent* event) override;
    void mousePressEvent(QMouseEvent* event) override;
    void mouseMoveEvent(QMouseEvent* event) override;
    void mouseReleaseEvent(QMouseEvent* event) override;
    void mouseDoubleClickEvent(QMouseEvent* event) override;
    void wheelEvent(QWheelEvent* event) override;
    void keyPressEvent(QKeyEvent* event) override;
    void keyReleaseEvent(QKeyEvent* event) override;
    void resizeEvent(QResizeEvent* event) override;
    void leaveEvent(QEvent* event) override;

private:
    enum class DragMode { None, Pan, SelectRect, SubtractRect, MoveContent };

    QPointF worldToWidget(double blockX, double blockZ) const;
    QPointF widgetToWorld(const QPointF& widgetPos) const;
    ChunkRect visibleChunks() const;

    void drawTiles(QPainter& painter, const ChunkRect& visible);
    void drawChunkGrid(QPainter& painter, const ChunkRect& visible);
    void drawExportRectangle(QPainter& painter);
    void drawArenas(QPainter& painter, const ChunkRect& visible);
    void drawSelection(QPainter& painter);
    void drawPastePreview(QPainter& painter);
    void drawWorldSpawn(QPainter& painter);
    void drawLegend(QPainter& painter);

    void requestMissingTiles();
    void setZoom(double zoom, const QPointF& anchorWidgetPos);

    Document* m_document = nullptr;
    TileCache m_tiles;
    Tool m_tool = Tool::Select;

    /// Pixels per block. 1.0 shows one block per pixel.
    double m_zoom = 0.25;
    /// World block position at the widget's top-left corner.
    QPointF m_origin{0.0, 0.0};
    int m_sliceY = 320;
    bool m_showChunkGrid = true;
    bool m_showArenaLabels = true;

    DragMode m_drag = DragMode::None;
    QPointF m_dragStartWidget;
    QPointF m_dragStartOrigin;
    QPoint m_dragStartChunk;
    QPoint m_dragCurrentChunk;
    bool m_spaceHeld = false;

    std::optional<ChunkRect> m_pastePreview;
    QPoint m_pasteOrigin;
    QPoint m_hoverChunk;
    bool m_hasHover = false;
};

} // namespace chunkdaddy
