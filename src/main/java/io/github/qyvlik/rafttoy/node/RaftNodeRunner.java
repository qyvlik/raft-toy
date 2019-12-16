package io.github.qyvlik.rafttoy.node;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;

public class RaftNodeRunner implements Runnable {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private Queue<RaftCommand> queue;
    private RaftNode raftNode;
    private int leaderHeartbeatTimes = 0;

    public RaftNodeRunner(Queue<RaftCommand> queue, RaftNode raftNode) {
        this.raftNode = raftNode;
        this.queue = queue;
    }

    public void send(RaftCommand raftCommand) {
        queue.add(raftCommand);
    }

    public RaftNode.RaftRole getRaftRole() {
        return this.raftNode.getRaftRole();
    }

    public String getNodeId() {
        return this.raftNode.getNodeId();
    }

    private void checkTimeout(RaftCommand.Type type) {
        // todo all roles receiveAppendEntries
        switch (raftNode.getRaftRole()) {
            case Candidate: {
                leaderHeartbeatTimes = 0;
                if (!type.equals(RaftCommand.Type.candidateTimeout)) {
                    break;
                }

                boolean candidateVoteTimeOut = raftNode.checkCandidateVoteTimeOut();
                if (candidateVoteTimeOut) {
                    boolean mostVoteGranted = raftNode.beCandidateAndSendVoteImmediately("voteTimeout");
                    if (mostVoteGranted) {
                        raftNode.beLeaderAndHeartbeatImmediately(1);
                    }
                }
            }
            break;
            case Follower: {
                leaderHeartbeatTimes = 0;
                if (!type.equals(RaftCommand.Type.followerTimeout)) {
                    break;
                }

                // 心跳超时，发起选举
                // 无任何请求持续一段时间，发起选举
                if (raftNode.checkFollowerTimeout()) {
                    boolean mostVoteGranted = raftNode.beCandidateAndSendVoteImmediately("heartbeatTimeout");
                    if (mostVoteGranted) {
                        raftNode.beLeaderAndHeartbeatImmediately(1);
                    }
                }
            }
            break;
            case Leader: {
                if (!type.equals(RaftCommand.Type.heartbeat)) {
                    break;
                }

                if (leaderHeartbeatTimes > 100 * Math.random()) {
//                 todo 让 Leader 故障
                    // logger.info("Leader heartbeat broken");
                    break;
                }

                // logger.info("Leader heartbeat");
                raftNode.heartbeat();

                leaderHeartbeatTimes++;

            }
            break;
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (Exception e) {

        }
    }

    @Override
    public void run() {
        // raftNode
        while (true) {
            RaftCommand raftCommand = queue.poll();
            if (raftCommand == null) {
                sleep(1);
                continue;
            }

            // logger.info("runner:{} command:{}", raftNode.getNodeId(), raftCommand);

            switch (raftCommand.getType()) {
                case heartbeat:
                case followerTimeout:
                case candidateTimeout:
                    checkTimeout(raftCommand.getType());
                    break;
                case requestVote: {
                    RequestVoteResult result = raftNode.receiveRequestVote((RequestVoteParams) raftCommand.getParams());

                    // logger.info("receiveRequestVote:{}, result:{}", raftNode.getNodeId(), result);

                    if (raftCommand.getCallback() != null && result != null) {
                        raftCommand.getCallback().ret(result);
                    }
                }
                break;
                case appendEntries: {
                    AppendEntriesResult result = raftNode.receiveAppendEntries((AppendEntriesParams) raftCommand.getParams());
                    if (raftCommand.getCallback() != null && result != null) {
                        raftCommand.getCallback().ret(result);
                    }
                }
                break;
                case clientCommand: {
                    ClientCommandResult result = raftNode.receiveClientCommand((ClientCommand) raftCommand.getParams());
                    if (raftCommand.getCallback() != null && result != null) {
                        raftCommand.getCallback().ret(result);
                    }
                }
                break;
                case printNodeLog: {
                    raftNode.printLog(5);
                }
                break;
            }
        }
    }
}
