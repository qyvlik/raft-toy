package io.github.qyvlik.rafttoy.node;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// https://github.com/maemual/raft-zh_cn/blob/master/raft-zh_cn.md#5-raft-%E4%B8%80%E8%87%B4%E6%80%A7%E7%AE%97%E6%B3%95
public class RaftNode {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final long heartbeatTimeout = 1000L;
    private final long followerLastHandleTimeout = heartbeatTimeout;
    private final long candidateVoteTimeout = 300L;

    private RaftRpcSender raftRpcSender;
    private String nodeId;                                // 节点 id
    private RaftRole raftRole;
    // 所有服务器上持久存在的
    private long currentTerm;                           // 服务器最后一次知道的任期号（初始化为 0，持续递增）
    private String votedFor;                            // 在当前获得选票的候选人的 Id
    private TreeMap<Long, RaftLog> log;                 // 日志条目集；每一个条目包含一个用户状态机执行的指令，和收到时的任期号
    // 所有服务器上经常变的
    private String leaderId;
    private long commitIndex;                           // 已知的最大的已经被提交的日志条目的索引值
    private long lastApplied;                           // 最后被应用到状态机的日志条目索引值（初始化为 0，持续递增）
    private Set<String> nodes;
    // 在领导人里经常改变的 （选举后重新初始化）
    private Map<String, Long> nextIndex;                  // 对于每一个服务器，需要发送给他的下一个日志条目的索引值（初始化为领导人最后索引值加一）;
    private Map<String, Long> matchIndex;                 // 对于每一个服务器，已经复制给他的日志的最高索引值
    private Long leaderHeartbeatTime;
    private Long candidateVoteTime;
    private Long followerLastHandleTime;                  // todo 只要接收到请求就设置时间？亦或者是正确成功处理请求再设置时间？

    public RaftNode(String nodeId, Set<String> nodes, RaftRpcSender raftRpcSender) {
//        logger.info("raft-node create:{}", nodeId);

        this.nodeId = nodeId;
        setRaftRole(RaftRole.Follower, "startUp");
        this.currentTerm = 0;                           // todo reload from store
        this.votedFor = null;
        this.log = new TreeMap<>();
        this.leaderId = null;
        this.setCommitIndex(0);                         // todo reload from store
        this.lastApplied = 0;                           // todo reload from store
        this.nodes = nodes;
        this.raftRpcSender = raftRpcSender;
        this.leaderHeartbeatTime = System.currentTimeMillis();
        this.candidateVoteTime = System.currentTimeMillis();
        this.followerLastHandleTime = System.currentTimeMillis();
    }

    public RaftRole getRaftRole() {
        return raftRole;
    }

    private void setRaftRole(RaftRole raftRole, String reason) {
        RaftRole oldRole = this.raftRole;
        this.raftRole = raftRole;

        if (oldRole == null || !raftRole.equals(oldRole)) {
            logger.info("node:{} change role:{} -> {}, reason:{}", nodeId, oldRole, raftRole, reason);
        }
    }

    public String getNodeId() {
        return nodeId;
    }

    public long getCommitIndex() {
        return commitIndex;
    }

    public void setCommitIndex(long commitIndex) {
        this.commitIndex = commitIndex;
    }

    public String getVotedFor() {
        return votedFor;
    }

    public void setVotedFor(String votedFor) {
        this.votedFor = votedFor;
    }

    public boolean checkLeaderHeartbeatTimeOut() {
        long currentTimeMillis = System.currentTimeMillis();
        if (this.leaderHeartbeatTime == null) {

//            return currentTimeMillis - this.startupTime > heartbeatTimeout * Math.random();
            return false;
        }

        // ~~info: 投票后，延后检查 leaderHeartbeatTime?~~

        long finalTimeout = (long) (heartbeatTimeout * (1 + Math.random()));

        boolean result = currentTimeMillis - this.leaderHeartbeatTime > finalTimeout;

//        if (result) {
//            logger.info("heartbeatTimeout, node:{} current-time:{}, last-heartbeat:{}, timeout:{}",
//                    this.getNodeId(), currentTimeMillis, leaderHeartbeatTime, finalTimeout);
//        }

        return result;
    }

    public boolean checkCandidateVoteTimeOut() {
        long currentTimeMillis = System.currentTimeMillis();
        if (this.candidateVoteTime == null) {

//            return currentTimeMillis - this.startupTime > candidateVoteTimeout * Math.random();
            return false;
        }

        long finalTimeout = (long) (candidateVoteTimeout * (1 + Math.random()));

        boolean result = currentTimeMillis - this.candidateVoteTime > finalTimeout;

//        if (result) {
//            logger.info("candidateVoteTimeOut, node:{} current-time:{}, candidate-vote:{}, timeout:{}",
//                    this.getNodeId(), currentTimeMillis, candidateVoteTime, finalTimeout);
//        }

        return result;

    }

    public boolean checkFollowerLastHandleTimeout() {
        long currentTimeMillis = System.currentTimeMillis();
        if (this.followerLastHandleTime == null) {

//            return currentTimeMillis - this.startupTime > followerLastHandleTimeout * Math.random();
            return false;
        }

        long finalTimeout = (long) (followerLastHandleTimeout * (1 + Math.random()));

        boolean result = currentTimeMillis - this.followerLastHandleTime > finalTimeout;
//        if (result) {
//            logger.info("followerLastHandleTimeout, node:{} current-time:{}, last-handle:{}, timeout:{}",
//                    this.getNodeId(), currentTimeMillis, followerLastHandleTime, finalTimeout);
//        }

        return result;
    }

    /**
     * 1. 如果 term < currentTerm 就返回 false （5.1 节）
     * 2. 如果日志在 prevLogIndex 位置处的日志条目的任期号和 prevLogTerm 不匹配，则返回 false （5.3 节）
     * 3. 如果已经存在的日志条目和新的产生冲突（索引值相同但是任期号不同），删除这一条和之后所有的 （5.3 节）
     * 4. 附加日志中尚未存在的任何新条目
     * 5. 如果 leaderCommit > commitIndex，令 commitIndex 等于 leaderCommit 和 新日志条目索引值中较小的一个
     *
     * @param params 附加日志 参数
     * @return 附加日志 结果
     */
    public AppendEntriesResult receiveAppendEntries(AppendEntriesParams params) {
        // todo check role
        // 1. 如果 term < currentTerm 就返回 false （5.1 节）
        if (params.getTerm() < this.currentTerm) {
            return new AppendEntriesResult(this.currentTerm, false);
        }

        this.leaderHeartbeatTime = System.currentTimeMillis();
        this.followerLastHandleTime = System.currentTimeMillis();

        if (params.getTerm() > this.currentTerm) {
            if (params.getEntries() != null && params.getEntries().size() > 0) {
                beFollowerIfCurrentTermLessThanTerm(params.getTerm(), "appendEntriesParamsBiggerTerm");
            } else {
                beFollowerIfCurrentTermLessThanTerm(params.getTerm(), "heartbeatParamsBiggerTerm");
            }
            return new AppendEntriesResult(this.currentTerm, false);
        }

        // this.term == this.currentTerm

        // 2. 如果日志在 prevLogIndex 位置处的日志条目的任期号和 prevLogTerm 不匹配，则返回 false （5.3 节）
        // info: 如果 非 Leader 节点 上位于 prevLogIndex 的日志任期号与 prevLogTerm 不匹配，则返回 false （5.3 节）

        // info: 心跳和AppendEntry的唯一区别在于一个有日志一个没有日志，也就是说除了不能给跟随者增加日志外，心跳也一定需要完成日志一致性检测

        RaftLog raftLog = this.log.get(params.getPrevLogIndex());
        if (raftLog != null && raftLog.getTerm().compareTo(params.getPrevLogTerm()) != 0) {
            return new AppendEntriesResult(this.currentTerm, false);
        }

        // 3. 如果已经存在的日志条目和新的产生冲突（索引值相同但是任期号不同），删除这一条和之后所有的 （5.3 节）
        // 4. 附加日志中尚未存在的任何新条目
        // todo 排序？应该是 按 logIndex 从小到大
        Long minLeaderLogIndex = null;
        if (params.getEntries() != null && params.getEntries().size() > 0) {
            for (RaftLog leaderLog : params.getEntries()) {

                if (minLeaderLogIndex == null) {
                    minLeaderLogIndex = leaderLog.getLogIndex();
                }
                if (minLeaderLogIndex > leaderLog.getLogIndex()) {
                    minLeaderLogIndex = leaderLog.getLogIndex();
                }

                // todo 使用额外的队列，保存 undo
                // todo 使用额外的队列，保存 redo
                // todo 对 state-machine 先执行 undo 再执行 redo

                RaftLog nodeLog = this.log.get(leaderLog.getLogIndex());
                if (nodeLog != null && nodeLog.getTerm().compareTo(leaderLog.getTerm()) != 0) {
                    // todo delete nodeLog need undo in state-machine
                    this.log.remove(leaderLog.getLogIndex());
                    this.log.put(leaderLog.getLogIndex(), leaderLog);
                } else {
                    // todo nodeLog need redo in state-machine
                    this.log.put(leaderLog.getLogIndex(), leaderLog);
                }
            }
        }


        // 5. 如果 leaderCommit > commitIndex，令 commitIndex 等于 leaderCommit 和 新日志条目索引值中较小的一个
        // info: 如果是心跳，则不用更新
        if (params.getLeaderCommit() > this.getCommitIndex() && minLeaderLogIndex != null) {
            this.setCommitIndex(Math.min(minLeaderLogIndex, params.getLeaderCommit()));
        }

        this.applyLogs();

        return new AppendEntriesResult(this.currentTerm, true);
    }

    /**
     * 1. 如果term < currentTerm返回 false （5.2 节）
     * 2. 如果 votedFor 为空或者为 candidateId，并且候选人的日志至少和自己一样新，那么就投票给他（5.2 节，5.4 节）
     *
     * @param params 请求投票 参数
     * @return 请求投票 结果
     */
    public RequestVoteResult receiveRequestVote(RequestVoteParams params) {
        // logger.info("receiveRequestVote:{}, params:{}", nodeId, params);
        // 1. 如果term < currentTerm返回 false （5.2 节）
        if (params.getTerm() < this.currentTerm) {
            return new RequestVoteResult(this.currentTerm, false);
        }

        // 非 Follower 立刻设置为 Follower
        if (params.getTerm() > this.currentTerm && !this.getRaftRole().equals(RaftRole.Follower)) {
            beFollowerIfCurrentTermLessThanTerm(params.getTerm(), "requestVoteParamsBiggerTerm, requestNode:" + params.getCandidateId());
            return new RequestVoteResult(this.currentTerm, false);
        }

        // this.term == this.currentTerm

        // 2. 如果 votedFor 为空或者为 candidateId，并且候选人的日志至少和自己一样新，那么就投票给他（5.2 节，5.4 节）
        // todo, 如果是 领导人，接收其他节点的竞选请求吗？

        if (this.getRaftRole().equals(RaftRole.Leader)) {
            logger.info("receiveRequestVote reject: node:{} is Leader, candidate:{}",
                    this.getNodeId(), params.getCandidateId());
            return new RequestVoteResult(this.currentTerm, false);
        }

        if (this.getVotedFor() == null || this.getVotedFor().equals(params.getCandidateId())) {
            if (params.getLastLogIndex().compareTo(this.getCommitIndex()) >= 0) {
                this.setVotedFor(params.getCandidateId());

                this.followerLastHandleTime = System.currentTimeMillis();

                return new RequestVoteResult(this.currentTerm, true);
            }
        }

        return new RequestVoteResult(this.currentTerm, false);
    }

    /**
     * 所有服务器 都会执行如下操作
     * - 如果 commitIndex > lastApplied，那么就 lastApplied 加一，并把log[lastApplied]应用到状态机中（5.3 节）
     */
    private void applyLogs() {
        while (this.getCommitIndex() > this.lastApplied) {
            this.lastApplied += 1;
            // 如果commitIndex > lastApplied，那么就 lastApplied 加一，并把log[lastApplied]应用到状态机中（5.3 节）
            RaftLog log = this.log.get(this.lastApplied);
            // todo 应用到状态机中
        }
    }

    /**
     * 所有服务器 都会执行如下操作
     * - 如果接收到的 RPC 请求或响应中，任期号T > currentTerm，那么就令 currentTerm 等于 T，并切换状态为跟随者（5.1 节）
     *
     * @param term
     * @return
     */
    private boolean beFollowerIfCurrentTermLessThanTerm(Long term, String reason) {
        if (term > this.currentTerm) {

            // logger.info("beFollowerIfCurrentTermLessThanTerm:{}, term:{}", this.getNodeId(), term);

            setRaftRole(RaftRole.Follower, reason);
            this.currentTerm = term;
            this.setVotedFor(null);                     // 清空
//            this.leaderHeartbeatTime = null;            // todo
//            this.followerLastHandleTime = null;         // todo
            this.candidateVoteTime = null;              // 清空 选举超时
            return true;
        }
        return false;
    }

    /**
     * 如果在超过选举超时时间的情况之前都没有收到领导人的心跳，或者是候选人请求投票的，就自己变成候选人
     * 注意，如果是已经投票了，则不用变成候选人
     */
    public boolean beCandidateBecauseHeartbeatTimeoutAndSendVoteImmediately() {
        setRaftRole(RaftRole.Candidate, "HeartbeatTimeout");
        this.currentTerm += 1;
        this.setVotedFor(this.getNodeId());
        this.leaderId = null;
        this.leaderHeartbeatTime = null;            // 清空 心跳超时
        return sendRequestVote();
    }

    public boolean beCandidateBecauseVoteTimeoutAndSendVoteImmediately() {
        // todo 选举过程超时后，需要让 任期+1吗？
        setRaftRole(RaftRole.Candidate, "voteTimeout");
        this.currentTerm += 1;
        this.setVotedFor(this.getNodeId());
        this.leaderId = null;
        this.leaderHeartbeatTime = null;            // 清空 心跳超时
        return sendRequestVote();
    }

    /**
     * - 在转变成候选人后就立即开始选举过程
     * * - 自增当前的任期号（currentTerm）
     * * - 给自己投票
     * * - 重置选举超时计时器
     * * - 发送请求投票的 RPC 给其他所有服务器
     * - 如果接收到大多数服务器的选票，那么就变成领导人
     * - 如果接收到来自新的领导人的附加日志 RPC，转变成跟随者
     * - 如果选举过程超时，再次发起一轮选举
     *
     * @return 成功获选，则为 true
     */
    public boolean sendRequestVote() {
        if (!this.getRaftRole().equals(RaftRole.Candidate)) {
            throw new RuntimeException("RaftRole must be Candidate");
        }

        this.candidateVoteTime = System.currentTimeMillis();

        RequestVoteParams params = new RequestVoteParams();
        params.setTerm(this.currentTerm);
        params.setCandidateId(this.nodeId);
        params.setLastLogIndex(this.getCommitIndex());
        if (this.getCommitIndex() == 0) {
            params.setLastLogTerm(0L);
        } else {
            // 当候选者发起投票时，LastLogIndex应该是当前日志的长度，而不是已提交的日志的长度
            params.setLastLogTerm(this.log.get(this.getCommitIndex()).getTerm());
        }

        // 需要投自己一票，设置为 1
        int voteGrantedCount = 1;
        int mostNodeSize = this.nodes.size() / 2 + 1;

        List<Future<RequestVoteResult>> futureList = new LinkedList<>();
        for (String node : this.nodes) {
            // 自己不用发送 rpc
            if (node.equals(this.getNodeId())) {
                continue;
            }
            Future<RequestVoteResult> voteFuture = this.raftRpcSender.requestVote(node, params);
            futureList.add(voteFuture);
        }

        boolean mustBeFollower = false;
        for (Future<RequestVoteResult> voteFuture : futureList) {
            RequestVoteResult requestVoteResult = null;
            try {
                requestVoteResult = voteFuture.get(10, TimeUnit.MILLISECONDS);
            } catch (InterruptedException | ExecutionException e) {
                // logger.error("get sendRequestVote from future error ", e);
            } catch (TimeoutException e) {
                // logger.debug("get sendRequestVote from future error ", e);
            }

            // todo, 检查返回的 term

            if (requestVoteResult != null) {

                if (requestVoteResult.getTerm() > this.currentTerm) {
                    mustBeFollower = true;
                    // todo check response term
                    beFollowerIfCurrentTermLessThanTerm(
                            requestVoteResult.getTerm(), "requestVoteResponseBiggerTerm");
                    break;
                }

                if (requestVoteResult.getVoteGranted()) {
                    voteGrantedCount += 1;
                }
            }
        }

        if (mustBeFollower) {
            return false;
        }

        // todo check sendRequestVote time out
        if (voteGrantedCount >= mostNodeSize) {
            setRaftRole(RaftRole.Leader, "winByVote, term:" + this.currentTerm);
            this.leaderId = this.nodeId;
            this.setVotedFor(null);
            this.leaderHeartbeatTime = null;            // 清空 心跳超时
            this.followerLastHandleTime = null;         // 清空 follower 无操作超时
            this.candidateVoteTime = null;              // 清空 选举超时
            return true;
        }

        return false;
    }

    // - 一旦成为领导人：发送空的附加日志 RPC（心跳）给其他所有的服务器；在一定的空余时间之后不停的重复发送，以阻止跟随者超时（5.2 节）
    // - 如果接收到来自客户端的请求：附加条目到本地日志中，在条目被应用到状态机后响应客户端（5.3 节）
    // - 如果对于一个跟随者，最后日志条目的索引值大于等于 nextIndex，那么：发送从 nextIndex 开始的所有日志条目：
    // * - 如果成功：更新相应跟随者的 nextIndex 和 matchIndex
    // * - 如果因为日志不一致而失败，减少 nextIndex 重试
    // - 如果存在一个满足N > commitIndex的 N，并且大多数的matchIndex[i] ≥ N成立，并且log[N].term == currentTerm成立，那么令 commitIndex 等于这个 N （5.3 和 5.4 节）

    /**
     * 一旦成为领导人：发送空的附加日志 RPC（心跳）给其他所有的服务器；在一定的空余时间之后不停的重复发送，以阻止跟随者超时（5.2 节）
     */
    public void beLeaderAndHeartbeatImmediately(int heartbeatImmediatelyTimes) {
        if (!this.getRaftRole().equals(RaftRole.Leader)) {
            throw new RuntimeException("Heartbeat only Leader");
        }
        while (heartbeatImmediatelyTimes-- > 0) {
            if (!heartbeat()) {
                break;
            }
        }
    }

    /**
     * 心跳
     *
     * @return 是否继续心跳
     */
    public boolean heartbeat() {
        if (!this.getRaftRole().equals(RaftRole.Leader)) {
            return false;
        }

        List<Future<AppendEntriesResult>> futureList = new LinkedList<>();
        for (String node : this.nodes) {
            if (node.equals(this.getNodeId())) {
                continue;
            }

            AppendEntriesParams params = new AppendEntriesParams();
            params.setTerm(this.currentTerm);
            params.setLeaderId(this.nodeId);
            params.setPrevLogIndex(-1L);   // todo 是否需要发送
            params.setPrevLogTerm(-1L);    // todo 是否需要发送
            params.setEntries(null);
            params.setLeaderCommit(this.getCommitIndex());

            Future<AppendEntriesResult> appendLogsFuture = this.raftRpcSender.appendEntries(node, params);
            futureList.add(appendLogsFuture);
            // todo 判断返回值？
            // todo 判断返回的任期？
        }
        boolean mustBeFollower = false;
        for (Future<AppendEntriesResult> appendLogsFuture : futureList) {
            AppendEntriesResult appendEntriesResult = null;
            try {
                appendEntriesResult = appendLogsFuture.get(10, TimeUnit.MILLISECONDS);
            } catch (InterruptedException | ExecutionException e) {
                // logger.error("get heartbeat from future error ", e);
            } catch (TimeoutException e) {
                // logger.error("get heartbeat from future error ", e);
            }

            if (appendEntriesResult != null) {
                if (appendEntriesResult.getTerm() > this.currentTerm) {
                    mustBeFollower = true;
                    // todo check response term
                    beFollowerIfCurrentTermLessThanTerm(appendEntriesResult.getTerm(), "heartbeatResponseBiggerTerm");
                    break;
                }
            }
        }

        return !mustBeFollower;
    }

    /**
     * 如果接收到来自客户端的请求：附加条目到本地日志中，在条目被应用到状态机后响应客户端（5.3 节）
     *
     * @param clientCommand
     */
    public void receiveCommand(ClientCommand clientCommand) {
        if (!this.raftRole.equals(RaftRole.Leader)) {
            return;
        }
        // todo
    }


    public enum RaftRole {
        Follower,
        Candidate,
        Leader
    }

}
