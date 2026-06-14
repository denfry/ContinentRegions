package com.example.continentregions.editor;

import com.example.continentregions.config.ConfigManager;
import com.example.continentregions.model.Continent;
import com.example.continentregions.model.ContinentPoint;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Converts between {@link Continent} and the editor JSON format
 * (technical specification section 9.1) using Gson.
 */
public final class ContinentJson {

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final ConfigManager config;

    public ContinentJson(ConfigManager config) {
        this.config = config;
    }

    public String toJson(Continent continent) {
        return gson.toJson(toDto(continent));
    }

    /** Serializes a save result: the stored continent plus any non-blocking warnings. */
    public String toSaveResult(Continent continent, List<String> warnings) {
        final SaveResultDto dto = new SaveResultDto();
        dto.continent = toDto(continent);
        dto.warnings = warnings != null ? warnings : List.of();
        return gson.toJson(dto);
    }

    public String toJsonList(Collection<Continent> continents) {
        final List<ContinentDto> dtos = new ArrayList<>(continents.size());
        for (Continent c : continents) {
            dtos.add(toDto(c));
        }
        return gson.toJson(dtos);
    }

    /**
     * Parses a continent from JSON, filling missing optional fields with config
     * defaults. The {@code idOverride} (from the URL path) wins when present.
     */
    public Continent fromJson(String body, String idOverride) {
        final ContinentDto dto = gson.fromJson(body, ContinentDto.class);
        if (dto == null) {
            throw new IllegalArgumentException("Empty request body");
        }
        return fromDto(dto, idOverride);
    }

    /** Parses a JSON array of continents (bulk import). */
    public List<Continent> fromJsonList(String body) {
        final ContinentDto[] dtos = gson.fromJson(body, ContinentDto[].class);
        if (dtos == null) {
            throw new IllegalArgumentException("Empty request body");
        }
        final List<Continent> out = new ArrayList<>(dtos.length);
        for (ContinentDto dto : dtos) {
            if (dto != null) {
                out.add(fromDto(dto, null));
            }
        }
        return out;
    }

    private Continent fromDto(ContinentDto dto, String idOverride) {
        final Continent c = new Continent();
        c.setId(idOverride != null ? idOverride : dto.id);
        c.setDisplayName(dto.displayName != null ? dto.displayName : c.getId());
        c.setRegionId(dto.regionId); // may be null; service applies the prefix
        c.setWorldName(dto.world);
        c.setMinY(dto.minY != null ? dto.minY : config.defaultMinY());
        c.setMaxY(dto.maxY != null ? dto.maxY : config.defaultMaxY());
        c.setPriority(dto.priority != null ? dto.priority : config.defaultPriority());
        c.setColor(dto.color != null ? dto.color : "#3B82F6");
        c.setFillOpacity(dto.fillOpacity != null ? dto.fillOpacity : config.defaultFillOpacity());
        c.setLineOpacity(dto.lineOpacity != null ? dto.lineOpacity : config.defaultLineOpacity());
        c.setHidden(dto.hidden != null && dto.hidden);

        final List<ContinentPoint> points = new ArrayList<>();
        if (dto.points != null) {
            for (PointDto p : dto.points) {
                points.add(new ContinentPoint(p.x, p.z));
            }
        }
        c.setPoints(points);

        if (dto.flags != null) {
            c.setFlags(new LinkedHashMap<>(dto.flags));
        }
        return c;
    }

    /** Parses a bare flags object: {@code { "pvp": "deny", ... }}. */
    public java.util.Map<String, String> flagsFromJson(String body) {
        @SuppressWarnings("unchecked")
        final java.util.Map<String, String> map = gson.fromJson(body, java.util.LinkedHashMap.class);
        return map != null ? map : new LinkedHashMap<>();
    }

    private ContinentDto toDto(Continent c) {
        final ContinentDto d = new ContinentDto();
        d.id = c.getId();
        d.displayName = c.getDisplayName();
        d.regionId = c.getRegionId();
        d.world = c.getWorldName();
        d.minY = c.getMinY();
        d.maxY = c.getMaxY();
        d.priority = c.getPriority();
        d.color = c.getColor();
        d.fillOpacity = c.getFillOpacity();
        d.lineOpacity = c.getLineOpacity();
        d.hidden = c.isHidden();
        d.points = new ArrayList<>();
        for (ContinentPoint p : c.getPoints()) {
            d.points.add(new PointDto(p.x(), p.z()));
        }
        d.flags = new LinkedHashMap<>(c.getFlags());
        return d;
    }

    /** Wire format of a continent. */
    static final class ContinentDto {
        String id;
        String displayName;
        String regionId;
        String world;
        Integer minY;
        Integer maxY;
        Integer priority;
        String color;
        Double fillOpacity;
        Double lineOpacity;
        Boolean hidden;
        List<PointDto> points;
        java.util.Map<String, String> flags;
    }

    /** Wire format returned by create/update: the continent plus validation warnings. */
    static final class SaveResultDto {
        ContinentDto continent;
        List<String> warnings;
    }

    static final class PointDto {
        int x;
        int z;

        PointDto() {
        }

        PointDto(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }
}
