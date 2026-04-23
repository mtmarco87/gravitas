package com.gravitas.entities.core;

import java.util.UUID;

/**
 * Common runtime/authored identity for entities that exist in the universe.
 */
public abstract class UniverseObject {

    /**
     * Stable runtime instance id. Procedural/generated objects rely on this as
     * their only id.
     */
    public final String id;

    /** Optional stable authored/template identity for hand-authored content. */
    public String authoredId;

    /** Human-readable display name. */
    public String name;

    /** If false, this object is excluded from active gameplay/rendering logic. */
    public boolean active = true;

    protected UniverseObject(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
    }
}