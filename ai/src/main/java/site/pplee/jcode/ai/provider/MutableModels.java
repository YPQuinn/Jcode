package site.pplee.jcode.ai.provider;

/**
 * Mutable {@link Models} seam for registering, replacing, or removing
 * providers at runtime. Mutations are explicit and local to the instance;
 * there is no global registry.
 */
public interface MutableModels extends Models {
    /** Register or replace the provider identified by {@link ModelProvider#id()}. */
    void setProvider(ModelProvider provider);

    /** Remove the provider with the given id; idempotent when absent. */
    void deleteProvider(String id);

    /** Remove all providers. */
    void clearProviders();
}
