package io.github.qyvlik.rafttoy.node;

public class RaftCommand {
    private Type type;
    private Object params;
    private Callback callback;

    public static RaftCommand heartbeat() {
        RaftCommand command = new RaftCommand();
        command.setCallback(null);
        command.setParams(null);
        command.setType(Type.heartbeat);
        return command;
    }

    public static RaftCommand followerTimeout() {
        RaftCommand command = new RaftCommand();
        command.setCallback(null);
        command.setParams(null);
        command.setType(Type.followerTimeout);
        return command;
    }

    public static RaftCommand candidateTimeout() {
        RaftCommand command = new RaftCommand();
        command.setCallback(null);
        command.setParams(null);
        command.setType(Type.candidateTimeout);
        return command;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public Object getParams() {
        return params;
    }

    public void setParams(Object params) {
        this.params = params;
    }

    public Callback getCallback() {
        return callback;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    @Override
    public String toString() {
        return "(" + type + ", params:" + params + ")";
    }

    public enum Type {
        heartbeat,
        followerTimeout,
        candidateTimeout,
        appendEntries,
        requestVote,
        clientCommand
    }

    public interface Callback {
        void ret(Object result);
    }
}
