package com.example.continentregions.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Domain model of a continent. This is the source of truth that gets persisted
 * to storage and projected onto a WorldGuard region and a BlueMap shape marker.
 *
 * <p>Mirrors section 16.1 of the technical specification.
 */
public final class Continent {

    private String id;
    private String displayName;
    private String regionId;
    private String worldName;
    private int minY;
    private int maxY;
    private int priority;
    private String color;
    private double fillOpacity;
    private double lineOpacity;
    private boolean hidden;
    private List<ContinentPoint> points;
    private Map<String, String> flags;

    public Continent() {
        this.points = new ArrayList<>();
        this.flags = new LinkedHashMap<>();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRegionId() {
        return regionId;
    }

    public void setRegionId(String regionId) {
        this.regionId = regionId;
    }

    public String getWorldName() {
        return worldName;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public int getMinY() {
        return minY;
    }

    public void setMinY(int minY) {
        this.minY = minY;
    }

    public int getMaxY() {
        return maxY;
    }

    public void setMaxY(int maxY) {
        this.maxY = maxY;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public double getFillOpacity() {
        return fillOpacity;
    }

    public void setFillOpacity(double fillOpacity) {
        this.fillOpacity = fillOpacity;
    }

    public double getLineOpacity() {
        return lineOpacity;
    }

    public void setLineOpacity(double lineOpacity) {
        this.lineOpacity = lineOpacity;
    }

    /** When {@code true} the continent is kept in storage/WorldGuard but hidden from BlueMap. */
    public boolean isHidden() {
        return hidden;
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

    public List<ContinentPoint> getPoints() {
        return points;
    }

    public void setPoints(List<ContinentPoint> points) {
        this.points = points != null ? points : new ArrayList<>();
    }

    public Map<String, String> getFlags() {
        return flags;
    }

    public void setFlags(Map<String, String> flags) {
        this.flags = flags != null ? flags : new LinkedHashMap<>();
    }

    @Override
    public String toString() {
        return "Continent{id='" + id + "', world='" + worldName + "', points=" + points.size() + '}';
    }
}
