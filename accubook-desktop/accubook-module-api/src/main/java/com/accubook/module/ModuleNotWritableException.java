package com.accubook.module;

/**
 * A mutation was attempted against a module that may not be written.
 *
 * <p>Thrown by the enforcement layer regardless of how the caller arrived - the
 * UI, a scheduler, an importer, a background job or a test harness. The message
 * distinguishes the cases because they need different answers from the customer:
 * a downgraded module needs a plan change, an expired subscription needs a
 * renewal, and an uninstalled module needs a different installer.
 */
public class ModuleNotWritableException extends RuntimeException {

    private final ModuleId moduleId;
    private final ModuleState state;
    private final String operation;

    public ModuleNotWritableException(ModuleId moduleId, ModuleState state, String operation) {
        super(buildMessage(moduleId, state, operation));
        this.moduleId = moduleId;
        this.state = state;
        this.operation = operation;
    }

    private static String buildMessage(ModuleId moduleId, ModuleState state, String operation) {
        String because = switch (state) {
            case READ_ONLY -> "it is read-only. This happens when the module is not in your "
                    + "current plan, or your subscription has expired or been suspended. "
                    + "Your existing records remain available to view and export.";
            case DORMANT -> "it is not installed in this build. Your existing records remain "
                    + "available through the records viewer and can be exported. "
                    + "Install the build that includes this module to make changes.";
            case ABSENT -> "it is not available in this installation.";
            case ACTIVE -> "of an internal error - an active module should permit this.";
        };
        return "Cannot " + operation + ": " + moduleId + " is unavailable because " + because;
    }

    public ModuleId moduleId() {
        return moduleId;
    }

    public ModuleState state() {
        return state;
    }

    public String operation() {
        return operation;
    }
}
