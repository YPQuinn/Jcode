package site.pplee.jcode.codingagent.settings;

/** Independently persisted authorization scopes for project-owned inputs. */
public enum ProjectTrustScope {
    SETTINGS("settings"),
    TEXT_RESOURCES("textResources");

    private final String jsonField;

    ProjectTrustScope(String jsonField) {
        this.jsonField = jsonField;
    }

    String jsonField() {
        return jsonField;
    }
}
