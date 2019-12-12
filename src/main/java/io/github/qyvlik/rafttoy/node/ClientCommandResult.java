package io.github.qyvlik.rafttoy.node;

public class ClientCommandResult {
    private String leaderId;
    private String result;

    public ClientCommandResult(String leaderId, String result) {
        this.leaderId = leaderId;
        this.result = result;
    }

    public String getLeaderId() {
        return leaderId;
    }

    public void setLeaderId(String leaderId) {
        this.leaderId = leaderId;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    @Override
    public String toString() {
        return "ClientCommandResult{" +
                "leaderId='" + leaderId + '\'' +
                ", result='" + result + '\'' +
                '}';
    }
}
