package com.accubook.module.plan;

/**
 * A commercial plan, as a build target.
 *
 * <p>Deliberately separate from the entitlement. A plan says what an image
 * <em>contains</em>; the entitlement says what a customer may <em>use</em>. The
 * two are allowed to disagree, and the disagreement is meaningful:
 *
 * <ul>
 *   <li>entitled but not installed - the customer needs a different installer</li>
 *   <li>installed but not entitled - the module is READ_ONLY</li>
 * </ul>
 *
 * <p>Nothing in the running application branches on this enum. It exists for the
 * build system, so the plan name never becomes a runtime authorization source.
 */
public enum BuildPlan {

    BASIC("basic"),
    PROFESSIONAL("professional"),
    ENTERPRISE("enterprise");

    private final String cliName;

    BuildPlan(String cliName) {
        this.cliName = cliName;
    }

    public String cliName() {
        return cliName;
    }

    public static BuildPlan fromCliName(String raw) {
        if (raw != null) {
            for (BuildPlan plan : values()) {
                if (plan.cliName.equalsIgnoreCase(raw.strip())) {
                    return plan;
                }
            }
        }
        throw new IllegalArgumentException(
                "Unknown plan '" + raw + "'. Expected one of: basic, professional, enterprise");
    }
}
