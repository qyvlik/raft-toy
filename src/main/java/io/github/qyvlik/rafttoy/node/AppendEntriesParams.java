package io.github.qyvlik.rafttoy.node;

import java.util.List;

// 附加日志 RPC：
public class AppendEntriesParams {
    private Long term;                      // 领导人的任期号
    private String leaderId;                // 领导人的 Id，以便于跟随者重定向请求
    private Long prevLogIndex;              // 新的日志条目紧随之前的索引值
    private Long prevLogTerm;               // prevLogIndex 条目的任期号
    private List<RaftLog> entries;          // 准备存储的日志条目（表示心跳时为空；一次性发送多个是为了提高效率）
    private Long leaderCommit;              // 领导人已经提交的日志的索引值

    public Long getTerm() {
        return term;
    }

    public void setTerm(Long term) {
        this.term = term;
    }

    public String getLeaderId() {
        return leaderId;
    }

    public void setLeaderId(String leaderId) {
        this.leaderId = leaderId;
    }

    public Long getPrevLogIndex() {
        return prevLogIndex;
    }

    public void setPrevLogIndex(Long prevLogIndex) {
        this.prevLogIndex = prevLogIndex;
    }

    public Long getPrevLogTerm() {
        return prevLogTerm;
    }

    public void setPrevLogTerm(Long prevLogTerm) {
        this.prevLogTerm = prevLogTerm;
    }

    public List<RaftLog> getEntries() {
        return entries;
    }

    public void setEntries(List<RaftLog> entries) {
        this.entries = entries;
    }

    public Long getLeaderCommit() {
        return leaderCommit;
    }

    public void setLeaderCommit(Long leaderCommit) {
        this.leaderCommit = leaderCommit;
    }

    @Override
    public String toString() {
        return "AppendEntriesParams{" +
                "term=" + term +
                ", leaderId='" + leaderId + '\'' +
                ", prevLogIndex=" + prevLogIndex +
                ", prevLogTerm=" + prevLogTerm +
                ", entries=" + entries +
                ", leaderCommit=" + leaderCommit +
                '}';
    }
}
