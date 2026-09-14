package gg.swim.chunkdaddy.worker.document;

import gg.swim.chunkdaddy.worker.conversion.ArenaTemplate;
import gg.swim.chunkdaddy.worker.conversion.TemplateSections;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-wide store of imported templates and their prepared section data.
 *
 * <p>Templates are shared across documents and tabs. Preparing a template's sections is
 * done once per template and cached: the design guide's rule is that decoding and
 * conversion happen once per source hash and target profile, never once per instance.
 */
public final class TemplateRegistry {
    private final Map<UUID, ArenaTemplate> templates = new LinkedHashMap<>();
    private final Map<UUID, TemplateSections> sections = new ConcurrentHashMap<>();

    public void add(ArenaTemplate template) {
        templates.put(template.id(), template);
    }

    public void remove(UUID id) {
        templates.remove(id);
        sections.remove(id);
    }

    public @Nullable ArenaTemplate get(UUID id) {
        return templates.get(id);
    }

    public ArenaTemplate require(UUID id) {
        ArenaTemplate template = templates.get(id);
        if (template == null) {
            throw new IllegalStateException("No template " + id + " in this session");
        }
        return template;
    }

    public Collection<ArenaTemplate> all() {
        return templates.values();
    }

    /** Prepared sections, built on first use and kept for the life of the session. */
    public TemplateSections sections(UUID id) {
        return sections.computeIfAbsent(id, key -> TemplateSections.build(require(key)));
    }

    /** Drop prepared section data without forgetting the templates themselves. */
    public void releaseSectionCache() {
        sections.clear();
    }

    public long cachedSectionCount() {
        long total = 0;
        for (TemplateSections value : sections.values()) {
            total += value.storedSections();
        }
        return total;
    }
}
