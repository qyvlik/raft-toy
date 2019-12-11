package io.github.qyvlik.rafttoy.node;

public class RaftLog {
    private Long logIndex;
    private Long term;
    private ClientCommand clientCommand;

    public Long getLogIndex() {
        return logIndex;
    }

    public void setLogIndex(Long logIndex) {
        this.logIndex = logIndex;
    }

    public Long getTerm() {
        return term;
    }

    public void setTerm(Long term) {
        this.term = term;
    }

    public ClientCommand getClientCommand() {
        return clientCommand;
    }

    public void setClientCommand(ClientCommand clientCommand) {
        this.clientCommand = clientCommand;
    }
}
