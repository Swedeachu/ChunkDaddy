#pragma once

#include "layout/Geometry.h"

#include <QString>
#include <QVector>
#include <cstdint>

namespace chunkdaddy {

/// One template participating in a grid, with the number of copies wanted.
struct GridTemplate {
    QString templateId;
    QString slug;
    /// Footprint in chunk columns, assuming a chunk-aligned minimum corner.
    int footprintChunksX = 1;
    int footprintChunksZ = 1;
    int sizeY = 1;
    int copyCount = 30;
    /// Minimum Y for this template's copies, in blocks.
    int minY = 0;
};

/// Grid parameters as the user sets them.
struct GridOptions {
    /// Number of completely empty chunk columns between neighbouring allocated
    /// footprints. Not a distance between centres and not a distance between anchors.
    int gapChunksX = 4;
    int gapChunksZ = 4;
    /// Columns in the grid; 0 asks the planner to suggest one.
    int columns = 0;
    int originChunkX = 0;
    int originChunkZ = 0;
    /// Extra empty chunks added around the whole grid in the export rectangle.
    int borderChunks = 0;
};

/// One arena placement produced by the planner.
struct GridPlacement {
    QString templateId;
    QString slug;
    int ordinal = 0;
    QString exportId;
    int minX = 0;
    int minY = 0;
    int minZ = 0;
    int sizeX = 0;
    int sizeY = 0;
    int sizeZ = 0;
    int row = 0;
    int column = 0;

    ChunkRect footprint() const;
};

/// The complete plan, including the numbers the user needs before committing.
struct GridPlan {
    QVector<GridPlacement> placements;
    int columns = 0;
    int rows = 0;
    /// Uniform cell footprint, from the largest included template.
    int cellChunksX = 0;
    int cellChunksZ = 0;
    /// Cell pitch: footprint plus gap.
    int pitchChunksX = 0;
    int pitchChunksZ = 0;
    int widthChunks = 0;
    int lengthChunks = 0;
    std::int64_t totalInstances = 0;
    std::int64_t generatedColumns = 0;
    /// Rough disk estimate, deliberately conservative.
    std::int64_t estimatedBytes = 0;
    ChunkRect bounds;
    QStringList warnings;
    bool valid = false;
    QString error;
};

/// Deterministic grid layout.
///
/// The first release uses one uniform cell size taken from the largest included
/// footprint. It is predictable and easy to inspect; smaller templates sit at their
/// cell's minimum corner. Gap is defined in chunk columns, so the visible block-to-block
/// gap can be larger where a template only partly fills its final chunk.
class GridPlanner {
public:
    /// Suggest a column count that roughly squares off the physical rectangle,
    /// accounting for unequal X and Z pitch.
    static int suggestColumns(std::int64_t totalInstances, int pitchChunksX, int pitchChunksZ,
                              int cellChunksX, int cellChunksZ);

    /// Build the plan. Never throws: an invalid configuration returns `valid == false`
    /// with an explanation the dialog can show.
    static GridPlan plan(const QVector<GridTemplate>& templates, const GridOptions& options);

    /// Ordering used for slots: template-major, then copy ordinal. Saved with the plan
    /// so placement never depends on filesystem enumeration order.
    static QVector<int> slotOrder(const QVector<GridTemplate>& templates);
};

} // namespace chunkdaddy
