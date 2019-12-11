package io.github.qyvlik.rafttoy.node;

public class RequestVoteResult {
    private Long term;              // 当前任期号，以便于候选人去更新自己的任期号
    private Boolean voteGranted;    // 候选人赢得了此张选票时为真

    public RequestVoteResult(Long term, Boolean voteGranted) {
        this.term = term;
        this.voteGranted = voteGranted;
    }

    public Long getTerm() {
        return term;
    }

    public void setTerm(Long term) {
        this.term = term;
    }

    public Boolean getVoteGranted() {
        return voteGranted;
    }

    public void setVoteGranted(Boolean voteGranted) {
        this.voteGranted = voteGranted;
    }

    @Override
    public String toString() {
        return "RequestVoteResult{" +
                "term=" + term +
                ", voteGranted=" + voteGranted +
                '}';
    }
}
