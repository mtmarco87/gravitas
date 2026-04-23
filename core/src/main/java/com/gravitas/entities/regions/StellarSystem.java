package com.gravitas.entities.regions;

public final class StellarSystem extends SpaceRegion {

    private final String textureNamespace;

    public StellarSystem(String id, String name, double centerX, double centerY, double centerZ, double radius,
            String textureNamespace) {
        super(id, name, centerX, centerY, centerZ, radius);
        this.textureNamespace = textureNamespace;
    }

    public String getTextureNamespace() {
        return textureNamespace;
    }
}