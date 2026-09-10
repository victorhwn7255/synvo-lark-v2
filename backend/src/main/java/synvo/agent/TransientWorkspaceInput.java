package synvo.agent;

/** Server-created workflow context; never accepted from a surface DTO or persisted. */
public record TransientWorkspaceInput(String text) {
    public TransientWorkspaceInput {
        if (text == null || text.isBlank() || text.length() > 20_000) {
            throw new IllegalArgumentException("Workspace input must be bounded");
        }
    }
    @Override public String toString() { return "TransientWorkspaceInput[protected]"; }
}
