#include "layout/GridPlanner.h"

#include "layout/InstanceNaming.h"

#include <algorithm>
#include <cmath>
#include <limits>

namespace chunkdaddy {

ChunkRect GridPlacement::footprint() const {
    return ChunkRect(blockToChunk(minX), blockToChunk(minZ),
                     blockToChunk(minX + sizeX - 1), blockToChunk(minZ + sizeZ - 1));
}

int GridPlanner::suggestColumns(std::int64_t totalInstances, int pitchChunksX, int pitchChunksZ,
                                int cellChunksX, int cellChunksZ) {
    if (totalInstances <= 0) {
        return 1;
    }
    int best = 1;
    double bestError = std::numeric_limits<double>::max();
    const int limit = static_cast<int>(std::min<std::int64_t>(totalInstances, 4096));

    for (int columns = 1; columns <= limit; ++columns) {
        const std::int64_t rows = (totalInstances + columns - 1) / columns;
        const double width =
            static_cast<double>(columns) * cellChunksX + static_cast<double>(columns - 1) * (pitchChunksX - cellChunksX);
        const double length =
            static_cast<double>(rows) * cellChunksZ + static_cast<double>(rows - 1) * (pitchChunksZ - cellChunksZ);
        if (width <= 0 || length <= 0) {
            continue;
        }
        // Compare the physical rectangle against a square. Using max/min rather than a
        // signed difference makes a 2:1 and a 1:2 rectangle equally bad, which is what a
        // user means by "roughly square".
        const double ratio = std::max(width, length) / std::min(width, length);
        if (ratio < bestError - 1e-9) {
            bestError = ratio;
            best = columns;
        }
    }
    return best;
}

QVector<int> GridPlanner::slotOrder(const QVector<GridTemplate>& templates) {
    QVector<int> order;
    std::int64_t total = 0;
    for (const GridTemplate& entry : templates) {
        total += std::max(0, entry.copyCount);
    }
    order.reserve(static_cast<int>(total));
    for (int index = 0; index < templates.size(); ++index) {
        for (int copy = 0; copy < templates[index].copyCount; ++copy) {
            order.append(index);
        }
    }
    return order;
}

GridPlan GridPlanner::plan(const QVector<GridTemplate>& templates, const GridOptions& options) {
    GridPlan plan;

    if (templates.isEmpty()) {
        plan.error = QStringLiteral("No templates selected.");
        return plan;
    }
    if (options.gapChunksX < 0 || options.gapChunksZ < 0) {
        plan.error = QStringLiteral("Chunk gaps cannot be negative.");
        return plan;
    }

    std::int64_t totalInstances = 0;
    int cellX = 0;
    int cellZ = 0;
    for (const GridTemplate& entry : templates) {
        if (entry.copyCount < 0) {
            plan.error = QStringLiteral("Copy count for '%1' cannot be negative.").arg(entry.slug);
            return plan;
        }
        if (entry.footprintChunksX <= 0 || entry.footprintChunksZ <= 0) {
            plan.error = QStringLiteral("Template '%1' has an empty footprint.").arg(entry.slug);
            return plan;
        }
        totalInstances += entry.copyCount;
        cellX = std::max(cellX, entry.footprintChunksX);
        cellZ = std::max(cellZ, entry.footprintChunksZ);
    }
    if (totalInstances == 0) {
        plan.error = QStringLiteral("Every copy count is zero, so there is nothing to place.");
        return plan;
    }
    if (totalInstances > 1'000'000) {
        plan.error = QStringLiteral("%1 instances is beyond what this planner will lay out.")
                         .arg(totalInstances);
        return plan;
    }

    plan.cellChunksX = cellX;
    plan.cellChunksZ = cellZ;
    plan.pitchChunksX = cellX + options.gapChunksX;
    plan.pitchChunksZ = cellZ + options.gapChunksZ;
    plan.totalInstances = totalInstances;

    int columns = options.columns > 0
                      ? options.columns
                      : suggestColumns(totalInstances, plan.pitchChunksX, plan.pitchChunksZ, cellX, cellZ);
    columns = std::max(1, columns);
    const int rows = static_cast<int>((totalInstances + columns - 1) / columns);

    plan.columns = columns;
    plan.rows = rows;
    // No exterior border in the grid's own dimensions; a border is added separately to
    // the export rectangle so the two numbers stay distinguishable.
    plan.widthChunks = columns * cellX + (columns - 1) * options.gapChunksX;
    plan.lengthChunks = rows * cellZ + (rows - 1) * options.gapChunksZ;

    const QVector<int> order = slotOrder(templates);
    QVector<int> nextOrdinal(templates.size(), 1);

    plan.placements.reserve(order.size());
    for (int slot = 0; slot < order.size(); ++slot) {
        const int templateIndex = order[slot];
        const GridTemplate& entry = templates[templateIndex];

        const int column = slot % columns;
        const int row = slot / columns;
        const int chunkX = options.originChunkX + column * plan.pitchChunksX;
        const int chunkZ = options.originChunkZ + row * plan.pitchChunksZ;

        GridPlacement placement;
        placement.templateId = entry.templateId;
        placement.slug = entry.slug;
        placement.ordinal = nextOrdinal[templateIndex]++;
        placement.exportId = InstanceNaming::exportId(entry.slug, placement.ordinal);
        placement.minX = chunkToBlock(chunkX);
        placement.minZ = chunkToBlock(chunkZ);
        placement.minY = entry.minY;
        // Sizes in blocks are only needed for the footprint and for the worker's
        // validation; the authoritative block data stays in the worker.
        placement.sizeX = entry.footprintChunksX * 16;
        placement.sizeZ = entry.footprintChunksZ * 16;
        placement.sizeY = entry.sizeY;
        placement.row = row;
        placement.column = column;
        plan.placements.append(placement);
    }

    plan.bounds = ChunkRect::ofSize(options.originChunkX, options.originChunkZ,
                                    plan.widthChunks, plan.lengthChunks);
    if (options.borderChunks > 0) {
        plan.bounds = plan.bounds.expanded(options.borderChunks);
    }
    plan.generatedColumns = plan.bounds.columnCount();
    // About 2 KiB per column is a deliberately pessimistic figure for a mixed
    // content-and-void rectangle; it exists to warn before a very large export, not to
    // predict the exact size.
    plan.estimatedBytes = plan.generatedColumns * 2048;

    const std::int64_t unusedCells =
        static_cast<std::int64_t>(columns) * rows - totalInstances;
    if (unusedCells > 0) {
        plan.warnings.append(
            QStringLiteral("%1 cell(s) in the last row are unused. They are still generated as "
                           "void columns inside the export rectangle.")
                .arg(unusedCells));
    }
    if (options.gapChunksX == 0 || options.gapChunksZ == 0) {
        plan.warnings.append(
            QStringLiteral("A zero chunk gap places arena footprints directly against each other. "
                           "ChunkDaddy controls placement only; gameplay isolation is the server's job."));
    }
    for (const GridTemplate& entry : templates) {
        if (entry.footprintChunksX < cellX || entry.footprintChunksZ < cellZ) {
            plan.warnings.append(
                QStringLiteral("'%1' is smaller than the uniform cell (%2x%3 vs %4x%5 chunks) and sits at "
                               "its cell's minimum corner.")
                    .arg(entry.slug)
                    .arg(entry.footprintChunksX).arg(entry.footprintChunksZ)
                    .arg(cellX).arg(cellZ));
        }
    }

    plan.valid = true;
    return plan;
}

} // namespace chunkdaddy
