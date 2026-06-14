package com.example.continentregions.storage;

import com.example.continentregions.model.Continent;

import java.util.Collection;
import java.util.Optional;

/**
 * Persistence abstraction for continents. The MVP ships a YAML implementation;
 * a SQLite implementation can be added later without touching callers
 * (technical specification section 10).
 */
public interface ContinentRepository {

    /** Opens the backing store and loads existing data into memory. */
    void init();

    /** @return all stored continents (defensive snapshot). */
    Collection<Continent> findAll();

    /** @return the continent with the given id, if present. */
    Optional<Continent> findById(String id);

    /** Inserts or updates the given continent. */
    void save(Continent continent);

    /**
     * Removes the continent with the given id.
     *
     * @return {@code true} if a continent was removed.
     */
    boolean delete(String id);

    /** Flushes and releases any resources. */
    void close();
}
