package io.github.qyvlik.rafttoy.node;

public class AppendEntriesResult {
    private Long term;          // 当前的任期号，用于领导人去更新自己
    private Boolean success;    // 跟随者包含了匹配上 prevLogIndex 和 prevLogTerm 的日志时为真

    public AppendEntriesResult(Long term, Boolean success) {
        this.term = term;
        this.success = success;
    }

    public Long getTerm() {
        return term;
    }

    public void setTerm(Long term) {
        this.term = term;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    @Override
    public String toString() {
        return "AppendEntriesResult{" +
                "term=" + term +
                ", success=" + success +
                '}';
    }
}
