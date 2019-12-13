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

    private final Logger logger = LoggerFactory.getLogger("RaftNode");
    private final long lastHeartbeatTimeout = 1000L;
    private final long lastHandleTimeout = lastHeartbeatTimeout;
    private final long candidateVoteTimeout = 300L;

    private final int APPEND_ENTRIES_DOWN_TO_FOLLOWER = -1;

    private RaftRpcSender raftRpcSender;
    private String nodeId;                              // 节点 id
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
    private Long candidateVoteTime;
    private Long lastHeartbeatTime;
    private Long lastHandleTime;                          // info: 只要接收到请求就设置时间？亦或者是正确成功处理请求再设置时间？

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
        this.lastHeartbeatTime = System.currentTimeMillis();
        this.candidateVoteTime = System.currentTimeMillis();
        this.lastHandleTime = System.currentTimeMillis();
        this.nextIndex = null;
        this.matchIndex = null;
    }

    public static <T> T getResultFromFuture(Future<T> future, long timeoutMs) {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
        }
        return null;
    }

    public RaftRole getRaftRole() {
        return raftRole;
    }

    private void setRaftRole(RaftRole raftRole, String reason) {
        RaftRole oldRole = this.raftRole;
        this.raftRole = raftRole;

        if (raftRole.equals(RaftRole.Follower)) {
            this.nextIndex = null;
            this.matchIndex = null;
        }

        if (oldRole == null || !raftRole.equals(oldRole)) {
            logger.info("{} change role:{} -> {}, reason:{}", nodeId, oldRole, raftRole, reason);
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

    private boolean checkLeaderHeartbeatTimeOut() {
        long currentTimeMillis = System.currentTimeMillis();
        if (this.lastHeartbeatTime == null) {
            return false;
        }

        long finalTimeout = (long) (lastHeartbeatTimeout * (1 + Math.random()));

        return currentTimeMillis - this.lastHeartbeatTime > finalTimeout;
    }

    private boolean checkFollowerLastHandleTimeout() {
        long currentTimeMillis = System.currentTimeMillis();
        if (this.lastHandleTime == null) {
            return false;
        }

        long finalTimeout = (long) (lastHandleTimeout * (1 + Math.random()));

        return currentTimeMillis - this.lastHandleTime > finalTimeout;
    }

    /**
     * 一个服务器节点继续保持着跟随者状态只要他从领导人或者候选者处接收到有效的 RPCs。
     */
    private void restFollowerTimeout(String reason) {
        if (!this.getRaftRole().equals(RaftRole.Follower)) {
            logger.error("restFollowerTimeout error : {} role:{}, reason:{}",
                    this.getNodeId(), this.getRaftRole(), reason);
        }

        this.lastHeartbeatTime = System.currentTimeMillis();
        this.lastHandleTime = System.currentTimeMillis();
    }

    /**
     * 自身投票后，再重置 Follower timeout
     *
     * @param votedFor 被投票节点
     */
    private void setVote(String votedFor) {
        this.votedFor = votedFor;
        this.candidateVoteTime = null;                          // 清空超时
        restFollowerTimeout("votedFro:" + votedFor);
    }

    /**
     * 给自己投票
     */
    private void voteSelf(String reason) {
        setRaftRole(RaftRole.Candidate, reason);
        this.leaderId = null;
        this.currentTerm += 1;
        this.votedFor = this.getNodeId();
        this.candidateVoteTime = System.currentTimeMillis();
        this.lastHeartbeatTime = null;
        this.lastHandleTime = null;
    }

    /**
     * 清空选举相关
     */
    private void clearVote() {
        this.setVotedFor(null);
        this.candidateVoteTime = null;
    }

    public boolean checkFollowerTimeout() {
        // 心跳超时，发起选举
        // 无任何请求持续一段时间，发起选举
        boolean followerLastHandleTimeout = this.checkFollowerLastHandleTimeout();
        boolean heartbeatTimeout = this.checkLeaderHeartbeatTimeOut();

        return followerLastHandleTimeout || heartbeatTimeout;
    }

    public boolean checkCandidateVoteTimeOut() {
        long currentTimeMillis = System.currentTimeMillis();
        if (this.candidateVoteTime == null) {
            return false;
        }

        long finalTimeout = (long) (candidateVoteTimeout * (1 + Math.random()));

        return currentTimeMillis - this.candidateVoteTime > finalTimeout;
    }


    public int getMost() {
        return this.nodes.size() / 2 + 1;
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
        // 1. 如果 term < currentTerm 就返回 false （5.1 节）
        // info 必须 this.term == this.currentTerm 才能进行正常的处理
        if (params.getTerm() < this.currentTerm) {
            return new AppendEntriesResult(this.currentTerm, false);
        }

        boolean isHeartbeat = params.getEntries() == null || params.getEntries().size() <= 0;

        // info: 重置 Follower 的 timeout
        if (this.getRaftRole().equals(RaftRole.Follower)) {
            restFollowerTimeout(isHeartbeat ? "heartbeat" : "appendEntries");
        }

        if (params.getTerm() > this.currentTerm) {
            beFollowerWithBiggerTerm(params);
        }

        // Candidate 如果接收到来自新的 Leader 的附加日志 RPC，转变成 Follower
        if (this.getRaftRole().equals(RaftRole.Candidate)) {
            candidateBeFollowerBecauseReceiveAppendEntries(params);
        }

        if (this.getRaftRole().equals(RaftRole.Leader)) {
            // todo Leader reject?
        }

        // 2. 如果日志在 prevLogIndex 位置处的日志条目的任期号和 prevLogTerm 不匹配，则返回 false （5.3 节）
        // info: 如果 非 Leader 节点 上位于 prevLogIndex 的日志任期号与 prevLogTerm 不匹配，则返回 false （5.3 节）
        // info: 心跳和AppendEntry的唯一区别在于一个有日志一个没有日志，也就是说除了不能给跟随者增加日志外，心跳也一定需要完成日志一致性检测

        RaftLog raftLog = this.log.get(params.getPrevLogIndex());
        if (raftLog != null && raftLog.getTerm().compareTo(params.getPrevLogTerm()) != 0) {
            logger.warn("receiveAppendEntries return {}, not match term:{}, raftLog:{}",
                    this.getNodeId(), params.getPrevLogTerm(), raftLog);
            return new AppendEntriesResult(this.currentTerm, false);
        }

        // 3. 如果已经存在的日志条目和新的产生冲突（索引值相同但是任期号不同），删除这一条和之后所有的 （5.3 节）
        // 4. 附加日志中尚未存在的任何新条目
        // info 排序？应该是 按 logIndex 从小到大
        Long minLeaderLogIndex = null;
        if (!isHeartbeat) {
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

        this.leaderId = params.getLeaderId();

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
        // 1. 如果term < currentTerm返回 false （5.2 节）
        if (params.getTerm() < this.currentTerm) {
            return new RequestVoteResult(this.currentTerm, false);
        }

        // 非 Follower 立刻设置为 Follower
        if (params.getTerm() > this.currentTerm) {
            beFollowerWithBiggerTerm(params);
        }

        // 2. 如果 votedFor 为空或者为 candidateId，并且候选人的日志至少和自己一样新，那么就投票给他（5.2 节，5.4 节）

        if (this.getRaftRole().equals(RaftRole.Leader)) {
            // info 如果是 领导人，接收其他节点的竞选请求吗？
            // todo, 如果 params.lastLogIndex > leader.lastLogIndex, leader 需要变为 follower 并投票吗？

            logger.info("{} is leader reject {} vote",
                    this.getNodeId(), params.getCandidateId());
            return new RequestVoteResult(this.currentTerm, false);
        }

        if (this.getVotedFor() == null || this.getVotedFor().equals(params.getCandidateId())) {
            // info: 判断 commitIndex 和 log.last().logIndex
            RaftLog lastRaftLog = this.getLastRaftLog();
            if (params.getLastLogIndex().compareTo(lastRaftLog.getLogIndex()) >= 0) {

                this.setVote(params.getCandidateId());

                return new RequestVoteResult(this.currentTerm, true);
            }
        }

        return new RequestVoteResult(this.currentTerm, false);
    }

    /**
     * 如果接收到来自客户端的请求：附加条目到本地日志中，在条目被应用到状态机后响应客户端（5.3 节）
     *
     * @param command
     */
    public ClientCommandResult receiveClientCommand(ClientCommand command) {
        if (!this.getRaftRole().equals(RaftRole.Leader)) {
            return new ClientCommandResult(this.leaderId, "this node not leader");
        }

        int successCount = appendEntries(command);
        // 被降级为 follower
        if (successCount == APPEND_ENTRIES_DOWN_TO_FOLLOWER) {

            return new ClientCommandResult(null, "down to follower");
        }

        int mostCount = getMost();
        if (successCount >= mostCount) {
            return new ClientCommandResult(this.leaderId,
                    "success, logId:" + this.commitIndex + ", term:" + this.currentTerm);
        }

        return new ClientCommandResult(this.leaderId, "can not send to most node");
    }

    /**
     * 所有服务器 都会执行如下操作
     * - 如果 commitIndex > lastApplied，那么就 lastApplied 加一，并把log[lastApplied]应用到状态机中（5.3 节）
     */
    private void applyLogs() {
        // todo undo, redo
        if (this.getRaftRole().equals(RaftRole.Leader)) {
            // 如果存在一个满足N > commitIndex的 N，并且大多数的matchIndex[i] ≥ N成立，并且log[N].term == currentTerm成立，
            // 那么令 commitIndex 等于这个 N （5.3 和 5.4 节）
            // [找出数组中出现次数最多的那个数——主元素问题](https://www.cnblogs.com/eniac12/p/5296139.html)

            int most = getMost();
            Long mostCommitIndex = null;
            Map<Long, Long> logIndexCountMap = new HashMap<>();
            for (Map.Entry<String, Long> entry : this.matchIndex.entrySet()) {
                Long times = logIndexCountMap.computeIfAbsent(entry.getValue(), k -> 0L);
                if (times + 1 >= most) {
                    mostCommitIndex = entry.getValue();
                    break;
                }
                logIndexCountMap.put(entry.getValue(), times + 1);
            }
            if (mostCommitIndex != null) {
                setCommitIndex(mostCommitIndex);
            }
        }

        while (this.getCommitIndex() > this.lastApplied) {
            this.lastApplied += 1;
            // 如果commitIndex > lastApplied，那么就 lastApplied 加一，并把log[lastApplied]应用到状态机中（5.3 节）
            RaftLog log = this.log.get(this.lastApplied);
            // todo 应用到状态机中
        }
    }

    /**
     * 任何节点收到 term > currentTerm，都必须切换为 Follower
     *
     * @param params params
     */
    private void beFollowerWithBiggerTerm(AppendEntriesParams params) {
        if (params.getTerm() > this.currentTerm && !this.getRaftRole().equals(RaftRole.Follower)) {
            String reason = "AppendEntriesParams:" + params.getLeaderId() + ", " + params.getTerm();
            setRaftRole(RaftRole.Follower, reason);
            this.currentTerm = params.getTerm();
            this.leaderId = params.getLeaderId();
            this.restFollowerTimeout(reason);
            this.clearVote();                  // 清空选举相关
        }
    }

    /**
     * 任何节点收到 term > currentTerm，都必须切换为 Follower
     *
     * @param params params
     */
    private void beFollowerWithBiggerTerm(RequestVoteParams params) {
        if (params.getTerm() > this.currentTerm) {
            String reason = "RequestVoteParams:" + params.getCandidateId() + ", " + params.getTerm();
            setRaftRole(RaftRole.Follower, reason);
            this.currentTerm = params.getTerm();
            this.leaderId = null;
            // 成功投票后再 清空 timeout，任期大不代表就一定可以当选 Leader
            // this.restFollowerTimeout(reason);
            this.clearVote();
        }
    }

    /**
     * 任何节点收到 term > currentTerm，都必须切换为 Follower
     *
     * @param node node
     * @param term term
     */
    private void beFollowerWithVoteResult(String node, Long term) {
        if (term > this.currentTerm) {
            String reason = "RequestVoteResult:" + node + ", " + term;
            setRaftRole(RaftRole.Follower, reason);
            this.currentTerm = term;
            this.leaderId = null;
            this.restFollowerTimeout(reason);
            this.clearVote();
        }
    }

    /**
     * 任何节点收到 term > currentTerm，都必须切换为 Follower
     *
     * @param node node
     * @param term term
     */
    private void beFollowerWithAppendEntriesResult(String node, Long term) {
        if (term > this.currentTerm) {
            String reason = "AppendEntriesResult:" + node + ", " + term;
            setRaftRole(RaftRole.Follower, reason);
            this.currentTerm = term;
            this.leaderId = null;
            this.restFollowerTimeout(reason);
            this.clearVote();
        }
    }

    /**
     * Candidate 如果接收到来自新的 Leader 的附加日志 RPC，转变成 Follower
     *
     * @param params params
     */
    private void candidateBeFollowerBecauseReceiveAppendEntries(AppendEntriesParams params) {
        if (!this.getRaftRole().equals(RaftRole.Candidate)) {
            throw new RuntimeException("candidateBeFollowerBecauseReceiveAppendEntries failure role must be Candidate");
        }

        if (params.getTerm() < this.currentTerm) {
            return;
        }
        String reason = "AppendEntriesParams:" + params.getLeaderId() + ", " + params.getTerm();
        setRaftRole(RaftRole.Follower, reason);
        this.currentTerm = params.getTerm();
        this.leaderId = params.getLeaderId();   // todo 是否直接认可？
        this.restFollowerTimeout(reason);
        this.clearVote();                       // 清空 选举
    }

    /**
     * - 如果在超过选举超时时间的情况之前都没有收到领导人的心跳，或者是候选人请求投票的，就自己变成候选人
     * - 注意，如果是已经投票了，则不用变成候选人
     * <p>
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
    public boolean beCandidateAndSendVoteImmediately(String reason) {
        this.voteSelf(reason);
        return sendRequestVote();
    }

    private boolean sendRequestVote() {
        if (!this.getRaftRole().equals(RaftRole.Candidate)) {
            throw new RuntimeException("RaftRole must be Candidate");
        }

        RaftLog lastRaftLog = getLastRaftLog();

        RequestVoteParams params = new RequestVoteParams();

        params.setTerm(this.currentTerm);
        params.setCandidateId(this.nodeId);

        // 当候选者发起投票时，LastLogIndex 应该是当前日志的最大下标，而不是已提交的日志的长度
        params.setLastLogIndex(lastRaftLog.getLogIndex());
        params.setLastLogTerm(lastRaftLog.getTerm());

        // 需要投自己一票，设置为 1
        int voteGrantedCount = 1;
        int mostNodeSize = getMost();
        Map<String, Future<RequestVoteResult>> futureMap = new HashMap<>();
        for (String node : this.nodes) {
            // 自己不用发送 rpc
            if (node.equals(this.getNodeId())) {
                continue;
            }
            Future<RequestVoteResult> voteFuture = this.raftRpcSender.requestVote(node, params);
            futureMap.put(node, voteFuture);
        }

        String biggerTermNode = null;
        long biggerTerm = this.currentTerm;
        for (Map.Entry<String, Future<RequestVoteResult>> voteFutureEntry : futureMap.entrySet()) {
            RequestVoteResult requestVoteResult = getResultFromFuture(voteFutureEntry.getValue(), 10);

            if (requestVoteResult != null) {

                if (requestVoteResult.getTerm() > biggerTerm) {
                    biggerTerm = requestVoteResult.getTerm();
                    biggerTermNode = voteFutureEntry.getKey();
                }

                if (requestVoteResult.getVoteGranted()) {
                    voteGrantedCount += 1;
                }
            }
        }

        boolean mustBeFollower = this.currentTerm < biggerTerm;
        if (mustBeFollower) {
            beFollowerWithVoteResult(biggerTermNode, biggerTerm);
        }

        // 没有被强制成为 Follower 且获得大多数投票
        if (!mustBeFollower && voteGrantedCount >= mostNodeSize) {
            setRaftRole(RaftRole.Leader, "winByVote, term:" + this.currentTerm);
            this.leaderId = this.nodeId;
            this.clearVote();                           // 清空 选举超时
            this.lastHeartbeatTime = null;              // 清空 心跳超时
            this.lastHandleTime = null;                 // 清空 follower 无操作超时

            // 当一个领导人刚获得权力的时候，他初始化所有的 nextIndex 值为自己的最后一条日志的index加1（图 7 中的 11）。
            this.nextIndex = new HashMap<>();
            this.matchIndex = new HashMap<>();
            for (String node : this.nodes) {
                this.nextIndex.put(node, lastRaftLog.getLogIndex() + 1);
            }

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

    private int appendEntries(ClientCommand command) {
        RaftLog lastRaftLog = getLastRaftLog();

        Long prevLogIndex = lastRaftLog.getLogIndex();
        Long prevLogTerm = lastRaftLog.getTerm();

        boolean isHeartbeat = true;
        List<RaftLog> entries = null;
        Long currentLogIndex = null;
        if (command != null) {
            currentLogIndex = lastRaftLog.getLogIndex() + 1;
            RaftLog raftLog = new RaftLog();
            raftLog.setLogIndex(currentLogIndex);
            raftLog.setTerm(this.currentTerm);
            raftLog.setClientCommand(command);
            entries = new LinkedList<>();
            entries.add(raftLog);

            this.log.put(currentLogIndex, raftLog);

            this.nextIndex.put(this.nodeId, currentLogIndex + 1);
            this.matchIndex.put(this.nodeId, currentLogIndex);

            isHeartbeat = false;
            // this.setCommitIndex(currentLogIndex);
        }

        if (!this.getRaftRole().equals(RaftRole.Leader)) {
            if (isHeartbeat) {
                throw new RuntimeException("heartbeat only Leader");
            } else {
                throw new RuntimeException("appendEntries only Leader");
            }
        }

        Map<String, Future<AppendEntriesResult>> futureMap = new HashMap<>();
        for (String node : this.nodes) {
            if (node.equals(this.getNodeId())) {
                continue;
            }
            AppendEntriesParams params = new AppendEntriesParams();
            params.setTerm(this.currentTerm);
            params.setLeaderId(this.nodeId);
            params.setPrevLogIndex(prevLogIndex);
            params.setPrevLogTerm(prevLogTerm);
            params.setEntries(entries);
            params.setLeaderCommit(this.getCommitIndex());

            Future<AppendEntriesResult> appendLogsFuture = this.raftRpcSender.appendEntries(node, params);
            futureMap.put(node, appendLogsFuture);
        }

        int successCount = 1;           // 自身已经添加
        String biggerTermNode = null;
        long biggerTerm = this.currentTerm;
        for (Map.Entry<String, Future<AppendEntriesResult>> entry : futureMap.entrySet()) {
            Future<AppendEntriesResult> appendLogsFuture = entry.getValue();
            AppendEntriesResult appendEntriesResult = getResultFromFuture(appendLogsFuture, 20);
            if (appendEntriesResult != null) {
                if (appendEntriesResult.getTerm() > biggerTerm) {

                    biggerTerm = appendEntriesResult.getTerm();
                    biggerTermNode = entry.getKey();
                }

                if (appendEntriesResult.getSuccess()) {
                    if (!isHeartbeat) {
                        // 维护设置 nextIndex
                        this.nextIndex.put(entry.getKey(), currentLogIndex + 1);
                        // 对于每一个服务器，已经复制给他的日志的最高索引值
                        this.matchIndex.put(entry.getKey(), currentLogIndex);
                    }
                    successCount++;
                } else {
                    if (!isHeartbeat) {
                        logger.warn("appendEntries response not match log index:{}", entry.getKey());
                        // 维护设置 nextIndex
                        this.nextIndex.put(entry.getKey(), this.nextIndex.get(entry.getKey()) - 1);

                        // todo 重发
                    }
                }
            }
        }

        boolean mustBeFollower = this.currentTerm < biggerTerm;
        if (mustBeFollower) {
            beFollowerWithAppendEntriesResult(biggerTermNode, biggerTerm);
            successCount = APPEND_ENTRIES_DOWN_TO_FOLLOWER;
        } else {

            // Leader:
            // 如果对于一个跟随者，最后日志条目的索引值大于等于 nextIndex，那么：发送从 nextIndex 开始的所有日志条目：
            //   如果成功：更新相应跟随者的 nextIndex 和 matchIndex
            //   如果因为日志不一致而失败，减少 nextIndex 重试
            // 如果存在一个满足N > commitIndex的 N，并且大多数的matchIndex[i] ≥ N成立，并且log[N].term == currentTerm成立，
            // 那么令 commitIndex 等于这个 N （5.3 和 5.4 节）

            int mostCount = getMost();
            if (!isHeartbeat && successCount >= mostCount) {
                this.applyLogs();
            }
        }

        return successCount;
    }

    /**
     * 一旦成为领导人：发送空的附加日志 RPC（心跳）给其他所有的服务器；在一定的空余时间之后不停的重复发送，以阻止跟随者超时（5.2 节）
     */
    public void beLeaderAndHeartbeatImmediately(int heartbeatImmediatelyTimes) {
        if (!this.getRaftRole().equals(RaftRole.Leader)) {
            throw new RuntimeException("heartbeat only Leader");
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
        return appendEntries(null) != APPEND_ENTRIES_DOWN_TO_FOLLOWER;
    }

    private RaftLog getLastRaftLog() {
        if (this.log.isEmpty()) {
            RaftLog raftLog = new RaftLog();
            raftLog.setLogIndex(0L);
            raftLog.setTerm(0L);
            raftLog.setClientCommand(null);
            return raftLog;
        }
        return this.log.lastEntry().getValue();
    }

    public void printLog(int tail) {
        int total = this.log.size();
        int head = total - tail - 1;
        int index = 0;
        StringBuilder sb = new StringBuilder("================= " + nodeId + "," + this.currentTerm + " =================\n");
        for (RaftLog raftLog : this.log.values()) {
            if (tail > 0 && index < head) {
                index++;
                continue;
            }
            index++;
            sb.append("|").append(raftLog.getTerm())
                    .append("|").append(raftLog.getLogIndex())
                    .append("|").append(raftLog.getClientCommand())
                    .append("\n");

        }

        sb.append("------------------------------------\n");
        sb.append("role:");
        sb.append(this.raftRole);
        sb.append("\n");
        sb.append("candidateVoteTime:");
        sb.append(this.candidateVoteTime);
        sb.append("\n");
        sb.append("voteFor:");
        sb.append(this.getVotedFor());
        sb.append("\n");

        if (this.getRaftRole().equals(RaftRole.Leader)) {
            sb.append("nextIndex:");
            sb.append(this.nextIndex);
            sb.append("\n");
            sb.append("matchIndex:");
            sb.append(this.matchIndex);
            sb.append("\n");
        }
        sb.append("------------------------------------\n");

        sb.append("================= " + nodeId + " =================");

        logger.info(sb.toString());
    }


    public enum RaftRole {
        Follower,
        Candidate,
        Leader
    }

}
